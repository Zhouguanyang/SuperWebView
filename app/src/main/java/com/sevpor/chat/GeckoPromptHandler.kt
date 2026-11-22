package com.sevpor.chat

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.Dialog
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.security.KeyChain
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PromptDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate.AlertPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.AuthPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.BasePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.BeforeUnloadPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ButtonPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.CertificateRequest
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ChoicePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ColorPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.DateTimePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.FilePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.FolderUploadPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PopupPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptInstanceDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse
import org.mozilla.geckoview.GeckoSession.PromptDelegate.RedirectPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.RepostConfirmPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.SharePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.TextPrompt
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class GeckoPromptHandler(
    private val activity: Activity,
    private val fileChooserHandler: FileChooserHandler,
) : PromptDelegate {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val activePrompts = linkedSetOf<ActivePrompt>()

    fun dismissAll() {
        val snapshot = synchronized(activePrompts) {
            val list = activePrompts.toList()
            activePrompts.clear()
            list
        }
        snapshot.forEach { active ->
            runCatching { active.dialog?.dismiss() }
            active.dismissOnce()
        }
    }

    override fun onAlertPrompt(
        session: GeckoSession,
        prompt: AlertPrompt,
    ): GeckoResult<PromptResponse> = showManagedDialog(prompt) { handle ->
        AlertDialog.Builder(activity)
            .setTitle(prompt.title.takeUnless { it.isNullOrBlank() })
            .setMessage(prompt.message.orEmpty())
            .setPositiveButton(android.R.string.ok) { _, _ ->
                handle.completeOnce(prompt.dismiss())
            }
            .create()
    }

    override fun onButtonPrompt(
        session: GeckoSession,
        prompt: ButtonPrompt,
    ): GeckoResult<PromptResponse> = showManagedDialog(prompt) { handle ->
        AlertDialog.Builder(activity)
            .setTitle(prompt.title.takeUnless { it.isNullOrBlank() })
            .setMessage(prompt.message.orEmpty())
            .setPositiveButton(android.R.string.ok) { _, _ ->
                handle.completeOnce(prompt.confirm(ButtonPrompt.Type.POSITIVE))
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                handle.completeOnce(prompt.confirm(ButtonPrompt.Type.NEGATIVE))
            }
            .create()
    }

    override fun onTextPrompt(
        session: GeckoSession,
        prompt: TextPrompt,
    ): GeckoResult<PromptResponse> = showManagedDialog(prompt) { handle ->
        val input = EditText(activity).apply {
            setSingleLine(true)
            setText(prompt.defaultValue.orEmpty())
            setSelection(text.length)
        }
        val container = createDialogInputContainer(input)

        AlertDialog.Builder(activity)
            .setTitle(prompt.title.takeUnless { it.isNullOrBlank() })
            .setMessage(prompt.message.takeUnless { it.isNullOrBlank() })
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                handle.completeOnce(prompt.confirm(input.text.toString()))
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                handle.completeOnce(prompt.dismiss())
            }
            .create()
    }

    override fun onChoicePrompt(
        session: GeckoSession,
        prompt: ChoicePrompt,
    ): GeckoResult<PromptResponse> {
        val flattened = flattenChoices(prompt.choices)
        if (flattened.isEmpty()) {
            return GeckoResult.fromValue(prompt.dismiss())
        }

        val labels = flattened.map { item ->
            if (item.choice.disabled) "${item.displayLabel} (—)" else item.displayLabel
        }.toTypedArray()

        return showManagedDialog(prompt) { handle ->
            val builder = AlertDialog.Builder(activity)
                .setTitle(prompt.title.takeUnless { it.isNullOrBlank() })

            if (prompt.type == ChoicePrompt.Type.MULTIPLE) {
                val checked = BooleanArray(flattened.size) { flattened[it].choice.selected }
                builder
                    .setMultiChoiceItems(labels, checked) { dialog, which, isChecked ->
                        if (flattened[which].choice.disabled) {
                            checked[which] = flattened[which].choice.selected
                            (dialog as? AlertDialog)?.listView?.setItemChecked(which, checked[which])
                        } else {
                            checked[which] = isChecked
                        }
                    }
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val selected = flattened
                            .filterIndexed { index, item -> checked[index] && !item.choice.disabled }
                            .map { it.choice }
                            .toTypedArray()
                        handle.completeOnce(prompt.confirm(selected))
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ ->
                        handle.completeOnce(prompt.dismiss())
                    }
            } else {
                val selectedIndex = flattened.indexOfFirst { it.choice.selected }
                builder
                    .setSingleChoiceItems(labels, selectedIndex) { dialog, which ->
                        val choice = flattened[which].choice
                        if (choice.disabled) {
                            (dialog as? AlertDialog)?.listView?.setItemChecked(which, false)
                            if (selectedIndex >= 0) {
                                (dialog as? AlertDialog)?.listView?.setItemChecked(selectedIndex, true)
                            }
                            return@setSingleChoiceItems
                        }
                        handle.completeOnce(prompt.confirm(choice))
                        dialog.dismiss()
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ ->
                        handle.completeOnce(prompt.dismiss())
                    }
            }
            builder.create()
        }
    }

    override fun onDateTimePrompt(
        session: GeckoSession,
        prompt: DateTimePrompt,
    ): GeckoResult<PromptResponse> {
        if (!isActivityAlive()) return GeckoResult.fromValue(prompt.dismiss())
        val active = createActivePrompt(prompt)

        when (prompt.type) {
            DateTimePrompt.Type.DATE -> showDatePicker(prompt, active) { year, month, day ->
                val formatted = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day)
                active.completeOnce(prompt.confirm(formatted))
            }
            DateTimePrompt.Type.TIME -> showTimePicker(prompt, active) { hour, minute ->
                val formatted = String.format(Locale.US, "%02d:%02d", hour, minute)
                active.completeOnce(prompt.confirm(formatted))
            }
            DateTimePrompt.Type.DATETIME_LOCAL -> {
                val initial = parseDateTimeCalendar(prompt.defaultValue)
                var proceedingToTime = false
                val dateDialog = DatePickerDialog(
                    activity,
                    { _, year, month, day ->
                        proceedingToTime = true
                        val timeDialog = TimePickerDialog(
                            activity,
                            { _, hour, minute ->
                                val formatted = String.format(
                                    Locale.US,
                                    "%04d-%02d-%02dT%02d:%02d",
                                    year,
                                    month + 1,
                                    day,
                                    hour,
                                    minute,
                                )
                                active.completeOnce(prompt.confirm(formatted))
                            },
                            initial.get(Calendar.HOUR_OF_DAY),
                            initial.get(Calendar.MINUTE),
                            true,
                        )
                        attachDialogLifecycle(timeDialog, active)
                        timeDialog.show()
                    },
                    initial.get(Calendar.YEAR),
                    initial.get(Calendar.MONTH),
                    initial.get(Calendar.DAY_OF_MONTH),
                )
                applyDateBounds(dateDialog, prompt.minValue, prompt.maxValue)
                active.dialog = dateDialog
                dateDialog.setOnCancelListener { active.dismissOnce() }
                dateDialog.setOnDismissListener {
                    if (!proceedingToTime) active.dismissOnce()
                }
                dateDialog.show()
            }
            DateTimePrompt.Type.MONTH,
            DateTimePrompt.Type.WEEK -> {
                val hintRes = if (prompt.type == DateTimePrompt.Type.MONTH) {
                    R.string.prompt_date_month_hint
                } else {
                    R.string.prompt_date_week_hint
                }
                val input = EditText(activity).apply {
                    setSingleLine(true)
                    hint = activity.getString(hintRes)
                    setText(prompt.defaultValue.orEmpty())
                    setSelection(text.length)
                }
                val dialog = AlertDialog.Builder(activity)
                    .setTitle(prompt.title.takeUnless { it.isNullOrBlank() })
                    .setView(createDialogInputContainer(input))
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        active.completeOnce(prompt.confirm(input.text.toString().trim()))
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ ->
                        active.dismissOnce()
                    }
                    .create()
                attachDialogLifecycle(dialog, active)
                dialog.show()
            }
            else -> active.dismissOnce()
        }

        return active.result
    }

    override fun onColorPrompt(
        session: GeckoSession,
        prompt: ColorPrompt,
    ): GeckoResult<PromptResponse> = showManagedDialog(prompt) { handle ->
        val density = activity.resources.displayMetrics.density
        val initialHex = normalizeHexColor(prompt.defaultValue) ?: "#000000"
        val preview = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (40 * density).toInt(),
            ).apply {
                bottomMargin = (12 * density).toInt()
            }
            setBackgroundColor(Color.parseColor(initialHex))
        }
        val hexInput = EditText(activity).apply {
            setSingleLine(true)
            hint = activity.getString(R.string.prompt_color_hint)
            setText(initialHex)
            setSelection(text.length)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    normalizeHexColor(s?.toString())?.let { valid ->
                        runCatching { preview.setBackgroundColor(Color.parseColor(valid)) }
                    }
                }
            })
        }

        val swatches = (prompt.predefinedValues?.toList().orEmpty()
            .mapNotNull { normalizeHexColor(it) }
            .ifEmpty { DEFAULT_COLOR_SWATCHES })

        val swatchRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            swatches.forEach { hex ->
                val size = (36 * density).toInt()
                val margin = (6 * density).toInt()
                val swatch = View(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(size, size).apply {
                        setMargins(margin, margin, margin, margin)
                    }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor(hex))
                        setStroke((1 * density).toInt(), Color.GRAY)
                    }
                    setOnClickListener {
                        hexInput.setText(hex)
                        hexInput.setSelection(hex.length)
                    }
                }
                addView(swatch)
            }
        }
        val swatchScroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(swatchRow)
        }

        val container = createDialogInputContainer(preview, swatchScroll, hexInput)
        AlertDialog.Builder(activity)
            .setTitle(prompt.title.takeUnless { it.isNullOrBlank() } ?: activity.getString(R.string.prompt_color_title))
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val valid = normalizeHexColor(hexInput.text.toString())
                if (valid != null) {
                    handle.completeOnce(prompt.confirm(valid))
                } else {
                    handle.dismissOnce()
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                handle.dismissOnce()
            }
            .create()
    }

    override fun onAuthPrompt(
        session: GeckoSession,
        prompt: AuthPrompt,
    ): GeckoResult<PromptResponse> = showManagedDialog(prompt) { handle ->
        val options = prompt.authOptions
        val onlyPassword = (options.flags and AuthPrompt.AuthOptions.Flags.ONLY_PASSWORD) != 0

        val usernameInput = EditText(activity).apply {
            hint = activity.getString(R.string.http_auth_username_hint)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
            setText(options.username.orEmpty())
            visibility = if (onlyPassword) View.GONE else View.VISIBLE
        }
        val passwordInput = EditText(activity).apply {
            hint = activity.getString(R.string.http_auth_password_hint)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(options.password.orEmpty())
        }

        val message = prompt.message.takeUnless { it.isNullOrBlank() }
            ?: activity.getString(R.string.http_auth_message, options.uri.orEmpty(), "")

        AlertDialog.Builder(activity)
            .setTitle(prompt.title.takeUnless { it.isNullOrBlank() } ?: activity.getString(R.string.http_auth_title))
            .setMessage(message.trim())
            .setView(createDialogInputContainer(usernameInput, passwordInput))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val password = passwordInput.text.toString()
                if (onlyPassword) {
                    handle.completeOnce(prompt.confirm(password))
                } else {
                    handle.completeOnce(prompt.confirm(usernameInput.text.toString(), password))
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                handle.dismissOnce()
            }
            .create()
    }

    override fun onFilePrompt(
        session: GeckoSession,
        prompt: FilePrompt,
    ): GeckoResult<PromptResponse> = fileChooserHandler.handleFilePrompt(prompt)

    override fun onFolderUploadPrompt(
        session: GeckoSession,
        prompt: FolderUploadPrompt,
    ): GeckoResult<PromptResponse> = showManagedDialog(prompt) { handle ->
        AlertDialog.Builder(activity)
            .setTitle(R.string.prompt_folder_upload_title)
            .setMessage(activity.getString(R.string.prompt_folder_upload_message, prompt.directoryName))
            .setPositiveButton(R.string.prompt_upload) { _, _ ->
                handle.completeOnce(prompt.confirm(AllowOrDeny.ALLOW))
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                handle.completeOnce(prompt.confirm(AllowOrDeny.DENY))
            }
            .create()
    }

    override fun onRequestCertificate(
        session: GeckoSession,
        request: CertificateRequest,
    ): GeckoResult<PromptResponse> {
        if (!isActivityAlive()) return GeckoResult.fromValue(request.dismiss())
        val active = createActivePrompt(request)

        try {
            KeyChain.choosePrivateKeyAlias(
                activity,
                { alias ->
                    mainHandler.post {
                        if (!alias.isNullOrBlank()) {
                            active.completeOnce(request.confirm(alias))
                        } else {
                            active.completeOnce(request.confirm(null))
                        }
                    }
                },
                null,
                request.issuers,
                request.host,
                -1,
                null,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unable to open client certificate chooser", e)
            active.dismissOnce()
        }

        return active.result
    }

    override fun onBeforeUnloadPrompt(
        session: GeckoSession,
        prompt: BeforeUnloadPrompt,
    ): GeckoResult<PromptResponse> = showManagedDialog(prompt) { handle ->
        AlertDialog.Builder(activity)
            .setTitle(prompt.title.takeUnless { it.isNullOrBlank() } ?: activity.getString(R.string.prompt_before_unload_title))
            .setMessage(R.string.prompt_before_unload_message)
            .setPositiveButton(R.string.prompt_leave) { _, _ ->
                handle.completeOnce(prompt.confirm(AllowOrDeny.ALLOW))
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                handle.completeOnce(prompt.confirm(AllowOrDeny.DENY))
            }
            .create()
    }

    override fun onRepostConfirmPrompt(
        session: GeckoSession,
        prompt: RepostConfirmPrompt,
    ): GeckoResult<PromptResponse> = showManagedDialog(prompt) { handle ->
        AlertDialog.Builder(activity)
            .setTitle(prompt.title.takeUnless { it.isNullOrBlank() } ?: activity.getString(R.string.prompt_repost_title))
            .setMessage(R.string.prompt_repost_message)
            .setPositiveButton(R.string.prompt_resend) { _, _ ->
                handle.completeOnce(prompt.confirm(AllowOrDeny.ALLOW))
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                handle.completeOnce(prompt.confirm(AllowOrDeny.DENY))
            }
            .create()
    }

    override fun onPopupPrompt(
        session: GeckoSession,
        prompt: PopupPrompt,
    ): GeckoResult<PromptResponse> {
        if (isTrustedWebUrl(prompt.targetUri)) {
            return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW))
        }
        return showManagedDialog(prompt) { handle ->
            AlertDialog.Builder(activity)
                .setTitle(R.string.prompt_popup_title)
                .setMessage(activity.getString(R.string.prompt_allow_navigation_message, prompt.targetUri.orEmpty()))
                .setPositiveButton(R.string.prompt_allow) { _, _ ->
                    handle.completeOnce(prompt.confirm(AllowOrDeny.ALLOW))
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    handle.completeOnce(prompt.confirm(AllowOrDeny.DENY))
                }
                .create()
        }
    }

    override fun onRedirectPrompt(
        session: GeckoSession,
        prompt: RedirectPrompt,
    ): GeckoResult<PromptResponse> {
        if (isTrustedWebUrl(prompt.targetUri)) {
            return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW))
        }
        return showManagedDialog(prompt) { handle ->
            AlertDialog.Builder(activity)
                .setTitle(R.string.prompt_redirect_title)
                .setMessage(activity.getString(R.string.prompt_allow_navigation_message, prompt.targetUri.orEmpty()))
                .setPositiveButton(R.string.prompt_allow) { _, _ ->
                    handle.completeOnce(prompt.confirm(AllowOrDeny.ALLOW))
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    handle.completeOnce(prompt.confirm(AllowOrDeny.DENY))
                }
                .create()
        }
    }

    override fun onSharePrompt(
        session: GeckoSession,
        prompt: SharePrompt,
    ): GeckoResult<PromptResponse> {
        if (!isActivityAlive()) {
            return GeckoResult.fromValue(prompt.confirm(SharePrompt.Result.ABORT))
        }

        val shareParts = listOfNotNull(
            prompt.text?.takeIf { it.isNotBlank() },
            prompt.uri?.takeIf { it.isNotBlank() },
        )
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            prompt.title?.takeIf { it.isNotBlank() }?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            putExtra(Intent.EXTRA_TEXT, shareParts.joinToString("\n"))
        }

        return try {
            val chooser = Intent.createChooser(sendIntent, activity.getString(R.string.prompt_share_title))
            activity.startActivity(chooser)
            GeckoResult.fromValue(prompt.confirm(SharePrompt.Result.SUCCESS))
        } catch (e: Exception) {
            Log.e(TAG, "Unable to launch system share sheet", e)
            GeckoResult.fromValue(prompt.confirm(SharePrompt.Result.FAILURE))
        }
    }

    private fun showDatePicker(
        prompt: DateTimePrompt,
        active: ActivePrompt,
        onDateSelected: (Int, Int, Int) -> Unit,
    ) {
        val calendar = parseDateTimeCalendar(prompt.defaultValue)
        val dialog = DatePickerDialog(
            activity,
            { _, year, month, day -> onDateSelected(year, month, day) },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH),
        )
        applyDateBounds(dialog, prompt.minValue, prompt.maxValue)
        attachDialogLifecycle(dialog, active)
        dialog.show()
    }

    private fun showTimePicker(
        prompt: DateTimePrompt,
        active: ActivePrompt,
        onTimeSelected: (Int, Int) -> Unit,
    ) {
        val calendar = parseDateTimeCalendar(prompt.defaultValue)
        val dialog = TimePickerDialog(
            activity,
            { _, hour, minute -> onTimeSelected(hour, minute) },
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            true,
        )
        attachDialogLifecycle(dialog, active)
        dialog.show()
    }

    private fun showManagedDialog(
        prompt: BasePrompt,
        buildDialog: (ActivePrompt) -> Dialog,
    ): GeckoResult<PromptResponse> {
        if (!isActivityAlive()) {
            return GeckoResult.fromValue(prompt.dismiss())
        }
        val active = createActivePrompt(prompt)
        try {
            val dialog = buildDialog(active)
            attachDialogLifecycle(dialog, active)
            dialog.show()
        } catch (e: Exception) {
            Log.e(TAG, "Unable to show prompt dialog", e)
            active.dismissOnce()
        }
        return active.result
    }

    private fun createActivePrompt(prompt: BasePrompt): ActivePrompt {
        val active = ActivePrompt(prompt) { completed ->
            synchronized(activePrompts) { activePrompts.remove(completed) }
        }
        synchronized(activePrompts) { activePrompts += active }
        prompt.setDelegate(object : PromptInstanceDelegate {
            override fun onPromptDismiss(basePrompt: BasePrompt) {
                mainHandler.post {
                    runCatching { active.dialog?.dismiss() }
                    active.dismissOnce()
                }
            }
        })
        return active
    }

    private fun attachDialogLifecycle(dialog: Dialog, active: ActivePrompt) {
        active.dialog = dialog
        dialog.setOnCancelListener { active.dismissOnce() }
        dialog.setOnDismissListener { active.dismissOnce() }
    }

    private fun createDialogInputContainer(vararg views: View): LinearLayout {
        val density = activity.resources.displayMetrics.density
        val horizontal = (24 * density).toInt()
        val vertical = (8 * density).toInt()
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(horizontal, vertical, horizontal, vertical)
            views.forEach { addView(it) }
        }
    }

    private fun isActivityAlive(): Boolean = !activity.isFinishing && !activity.isDestroyed

    private data class FlattenedChoice(
        val choice: ChoicePrompt.Choice,
        val displayLabel: String,
    )

    private fun flattenChoices(
        choices: Array<ChoicePrompt.Choice>?,
        prefix: String = "",
    ): List<FlattenedChoice> {
        if (choices.isNullOrEmpty()) return emptyList()
        val output = mutableListOf<FlattenedChoice>()
        choices.forEach { choice ->
            if (choice.separator) return@forEach
            val subItems = choice.items
            if (!subItems.isNullOrEmpty()) {
                val groupLabel = choice.label.orEmpty().trim()
                val nextPrefix = if (groupLabel.isNotEmpty()) "$groupLabel / " else prefix
                output += flattenChoices(subItems, nextPrefix)
            } else {
                output += FlattenedChoice(
                    choice = choice,
                    displayLabel = "$prefix${choice.label.orEmpty()}",
                )
            }
        }
        return output
    }

    private fun parseDateTimeCalendar(value: String?): Calendar {
        val calendar = Calendar.getInstance()
        if (value.isNullOrBlank()) return calendar

        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm",
            "yyyy-MM-dd",
            "HH:mm:ss",
            "HH:mm",
        )
        for (pattern in patterns) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }.parse(value)
            }.getOrNull()
            if (parsed != null) {
                val parsedCal = Calendar.getInstance().apply { time = parsed }
                if (pattern.startsWith("HH")) {
                    calendar.set(Calendar.HOUR_OF_DAY, parsedCal.get(Calendar.HOUR_OF_DAY))
                    calendar.set(Calendar.MINUTE, parsedCal.get(Calendar.MINUTE))
                } else {
                    calendar.time = parsed
                }
                return calendar
            }
        }
        return calendar
    }

    private fun applyDateBounds(dialog: DatePickerDialog, minValue: String?, maxValue: String?) {
        parseDateMillis(minValue)?.let { dialog.datePicker.minDate = it }
        parseDateMillis(maxValue)?.let { dialog.datePicker.maxDate = it }
    }

    private fun parseDateMillis(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val datePart = value.substringBefore('T')
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }.parse(datePart)?.time
        }.getOrNull()
    }

    private fun normalizeHexColor(value: String?): String? {
        val trimmed = value?.trim()?.lowercase(Locale.US) ?: return null
        val withHash = if (trimmed.startsWith("#")) trimmed else "#$trimmed"
        return if (Regex("^#[0-9a-f]{6}$").matches(withHash)) withHash else null
    }

    private class ActivePrompt(
        private val prompt: BasePrompt,
        private val onFinished: (ActivePrompt) -> Unit,
    ) {
        val result: GeckoResult<PromptResponse> = GeckoResult()
        var dialog: Dialog? = null
        private val completed = AtomicBoolean(false)

        fun completeOnce(response: PromptResponse) {
            if (completed.compareAndSet(false, true)) {
                onFinished(this)
                result.complete(response)
            }
        }

        fun dismissOnce() {
            completeOnce(prompt.dismiss())
        }
    }

    private companion object {
        val DEFAULT_COLOR_SWATCHES = listOf(
            "#000000", "#ffffff", "#ef4444", "#f97316", "#eab308",
            "#22c55e", "#06b6d4", "#3b82f6", "#8b5cf6", "#ec4899",
        )
    }
}
