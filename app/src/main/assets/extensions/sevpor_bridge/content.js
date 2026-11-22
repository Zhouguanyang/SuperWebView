(function () {
  if (window.__sevporGeckoBridgeInstalled) return;
  window.__sevporGeckoBridgeInstalled = true;

  var NATIVE_APP = 'sevpor_bridge';
  var DEFAULT_MIME_TYPE = 'application/octet-stream';
  var CHUNK_BYTES = 255 * 1024;
  var SESSION_CHECK_INTERVAL_MS = 60 * 60 * 1000;

  var port = null;
  var lastNavCanGoBack = null;
  var lastVisibleAt = document.visibilityState === 'visible' ? Date.now() : 0;
  var lastSessionCheckAt = Date.now();
  var sessionRefreshInFlight = false;

  function getPageSevporNav() {
    try {
      var pageWindow = window.wrappedJSObject || window;
      var nav = pageWindow && pageWindow.SevporNav;
      return nav && typeof nav === 'object' ? nav : null;
    } catch (e) {
      return null;
    }
  }

  function querySevporNavCanGoBack() {
    try {
      var nav = getPageSevporNav();
      if (!nav || typeof nav.goBack !== 'function') return false;
      if (typeof nav.canGoBack === 'function') {
        return !!nav.canGoBack();
      }
      return false;
    } catch (e) {
      return false;
    }
  }

  function postToPort(payload) {
    if (!port) return;
    try {
      port.postMessage(payload);
    } catch (e) {
      port = null;
    }
  }

  function syncNavState(force) {
    if (!force && document.visibilityState !== 'visible') return;
    var canGoBack = querySevporNavCanGoBack();
    if (!force && canGoBack === lastNavCanGoBack) return;
    lastNavCanGoBack = canGoBack;
    postToPort({
      type: 'sevporNavState',
      canGoBack: canGoBack
    });
  }

  function handleNativeGoBack(requestId) {
    var handled = false;
    try {
      var nav = getPageSevporNav();
      if (nav && typeof nav.goBack === 'function') {
        var canGoBack = typeof nav.canGoBack === 'function' ? !!nav.canGoBack() : true;
        if (canGoBack) {
          nav.goBack();
          handled = true;
          window.setTimeout(function () {
            syncNavState(true);
          }, 0);
        }
      }
    } catch (e) {
      handled = false;
    }
    postToPort({
      type: 'goBackResult',
      requestId: requestId || '',
      handled: handled
    });
  }

  function pageFetch(url, init) {
    if (typeof content !== 'undefined' && typeof content.fetch === 'function') {
      if (init !== undefined) {
        var safeInit = typeof cloneInto === 'function' ? cloneInto(init, window) : init;
        return content.fetch(url, safeInit);
      }
      return content.fetch(url);
    }
    return init !== undefined ? window.fetch(url, init) : window.fetch(url);
  }

  function handleForegroundResume() {
    try {
      var now = Date.now();
      if (
        document.visibilityState === 'visible' &&
        now - lastVisibleAt > 1000
      ) {
        lastVisibleAt = now;
        document.dispatchEvent(new Event('visibilitychange'));
      }

      if (
        sessionRefreshInFlight ||
        now - lastSessionCheckAt < SESSION_CHECK_INTERVAL_MS
      ) {
        return;
      }

      sessionRefreshInFlight = true;
      function clearInFlight() {
        sessionRefreshInFlight = false;
      }

      pageFetch('/api/auth/get-session', {
        method: 'GET',
        credentials: 'include',
        cache: 'no-store'
      })
        .then(function (response) {
          if (!response.ok) return null;
          lastSessionCheckAt = Date.now();
          return response.text().then(function (text) {
            if (!text) return null;
            return JSON.parse(text);
          }).catch(function () {
            return null;
          });
        })
        .then(function (data) {
          if (data && data.needsRefresh === true) {
            return pageFetch('/api/auth/get-session', {
              method: 'POST',
              credentials: 'include',
              cache: 'no-store'
            });
          }
        })
        .catch(function () {})
        .then(clearInFlight, clearInFlight);
    } catch (e) {}
  }

  function connectNativePort() {
    if (port) return;
    try {
      port = browser.runtime.connectNative(NATIVE_APP);
      port.onMessage.addListener(function (message) {
        if (!message || typeof message !== 'object') return;
        if (message.type === 'goBack') {
          handleNativeGoBack(message.requestId);
        } else if (message.type === 'downloadPageUrl' && message.url) {
          sendPageDownload(message.url, message.fileName || 'download');
        } else if (message.type === 'foregroundResume') {
          handleForegroundResume();
        }
      });
      port.onDisconnect.addListener(function () {
        port = null;
      });
      syncNavState(true);
    } catch (e) {
      port = null;
    }
  }

  function nextDownloadId() {
    return String(Date.now()) + '_' + String(Math.random()).slice(2);
  }

  function sendNative(payload) {
    return browser.runtime.sendNativeMessage(NATIVE_APP, payload);
  }

  function assertOk(response, errorMessage) {
    if (!response || response.ok !== true) {
      throw new Error(errorMessage);
    }
  }

  // Read each Blob slice through the content script's own FileReader.readAsDataURL so
  // Gecko encodes base64 natively and returns a primitive string, avoiding Firefox MV2
  // Xray issues with page-compartment ReadableStream/Uint8Array objects from content.fetch.
  function readBlobSliceAsBase64(blob, start, end) {
    return new Promise(function (resolve, reject) {
      var reader = new FileReader();
      reader.onload = function () {
        var dataUrl = typeof reader.result === 'string' ? reader.result : '';
        var marker = ';base64,';
        var markerIndex = dataUrl.indexOf(marker);
        if (markerIndex < 0) {
          reject(new Error('FileReader did not return base64 data URL'));
          return;
        }
        var base64 = dataUrl.slice(markerIndex + marker.length);
        if (!base64) {
          reject(new Error('FileReader returned empty chunk'));
          return;
        }
        resolve(base64);
      };
      reader.onerror = function () {
        reject(reader.error || new Error('FileReader failed'));
      };
      reader.onabort = function () {
        reject(new Error('FileReader aborted'));
      };
      reader.readAsDataURL(blob.slice(start, end));
    });
  }

  function sendBlobChunks(id, blob) {
    var totalSize = typeof blob.size === 'number' && blob.size >= 0 ? blob.size : -1;
    if (totalSize < 0) {
      return Promise.reject(new Error('Invalid blob size'));
    }

    var offset = 0;
    function readNext() {
      if (offset >= totalSize) return Promise.resolve();
      var end = Math.min(offset + CHUNK_BYTES, totalSize);
      return readBlobSliceAsBase64(blob, offset, end).then(function (base64) {
        offset = end;
        return sendNative({
          type: 'appendDownloadChunk',
          id: id,
          data: base64
        }).then(function (response) {
          assertOk(response, 'Native download chunk rejected');
          return readNext();
        });
      });
    }
    return readNext();
  }

  function sendPageDownload(pageUrl, fileName) {
    var id = nextDownloadId();
    var started = false;
    return pageFetch(pageUrl)
      .then(function (response) {
        if (!response.ok) {
          throw new Error('HTTP ' + response.status);
        }
        return response.blob();
      })
      .then(function (blob) {
        return sendNative({
          type: 'beginDownload',
          id: id,
          mimeType: blob.type || DEFAULT_MIME_TYPE,
          fileName: fileName || 'download'
        }).then(function (response) {
          assertOk(response, 'Native download rejected');
          started = true;
          return sendBlobChunks(id, blob);
        }).then(function () {
          return sendNative({
            type: 'finishDownload',
            id: id
          }).then(function (response) {
            assertOk(response, 'Native download finish failed');
          });
        });
      })
      .catch(function (error) {
        if (started) {
          sendNative({ type: 'cancelDownload', id: id }).catch(function () {});
        } else {
          sendNative({ type: 'downloadFailed' }).catch(function () {});
        }
        console.error('Sevpor GeckoView download failed', error);
      });
  }

  function fileNameFromAnchor(anchor) {
    var name = anchor.getAttribute('download') || '';
    return name || 'download';
  }

  function installDetachedAnchorClickHook() {
    try {
      var pageWindow = window.wrappedJSObject;
      if (
        !pageWindow ||
        !pageWindow.HTMLAnchorElement ||
        !pageWindow.HTMLAnchorElement.prototype ||
        typeof exportFunction !== 'function'
      ) {
        return;
      }
      var proto = pageWindow.HTMLAnchorElement.prototype;
      var originalClick = proto.click;
      if (typeof originalClick !== 'function') return;

      exportFunction(
        function () {
          try {
            var href = this && typeof this.href === 'string' ? this.href : '';
            var isBlob = href.indexOf('blob:') === 0;
            var isDataDownload =
              href.indexOf('data:') === 0 &&
              typeof this.hasAttribute === 'function' &&
              this.hasAttribute('download');
            if ((isBlob || isDataDownload) && !this.isConnected) {
              var name =
                (typeof this.getAttribute === 'function' && this.getAttribute('download')) ||
                'download';
              sendPageDownload(href, name);
              return;
            }
          } catch (e) {}
          return originalClick.call(this);
        },
        proto,
        { defineAs: 'click' }
      );
    } catch (e) {}
  }

  installDetachedAnchorClickHook();

  function findDownloadAnchor(event) {
    var target = event && event.target;
    if (target && typeof target.closest === 'function') {
      var direct =
        target.closest('a[href^="blob:"]') ||
        target.closest('a[href^="data:"][download]');
      if (direct) return direct;
    }
    if (event && typeof event.composedPath === 'function') {
      var path = event.composedPath();
      for (var i = 0; i < path.length; i++) {
        var node = path[i];
        if (!node || node.nodeType !== 1 || node.tagName !== 'A') continue;
        var href = typeof node.href === 'string' ? node.href : '';
        if (
          href.indexOf('blob:') === 0 ||
          (href.indexOf('data:') === 0 && typeof node.hasAttribute === 'function' && node.hasAttribute('download'))
        ) {
          return node;
        }
      }
    }
    return null;
  }

  document.addEventListener(
    'click',
    function (event) {
      var anchor = findDownloadAnchor(event);
      if (!anchor) return;
      event.preventDefault();
      sendPageDownload(anchor.href, fileNameFromAnchor(anchor));
    },
    true
  );

  if (window === window.top) {
    document.addEventListener(
      'visibilitychange',
      function () {
        if (document.visibilityState === 'visible') {
          lastVisibleAt = Date.now();
          syncNavState(false);
        }
      },
      true
    );

    connectNativePort();
    window.addEventListener('popstate', function () {
      window.setTimeout(function () {
        syncNavState(false);
      }, 0);
    });
    window.addEventListener('hashchange', function () {
      window.setTimeout(function () {
        syncNavState(false);
      }, 0);
    });
    window.addEventListener('pageshow', function () {
      connectNativePort();
      syncNavState(true);
    });
    window.addEventListener('focus', function () {
      syncNavState(false);
    });
    window.setInterval(function () {
      syncNavState(false);
    }, 500);
  }
})();
