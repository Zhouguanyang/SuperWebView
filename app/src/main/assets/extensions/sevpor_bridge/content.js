(function () {
  if (window.__sevporGeckoBridgeInstalled) return;
  window.__sevporGeckoBridgeInstalled = true;

  var NATIVE_APP = 'sevpor_bridge';
  var DEFAULT_MIME_TYPE = 'application/octet-stream';
  var CHUNK_BYTES = 256 * 1024;
  var BYTE_STRING_BATCH = 32768;

  var port = null;
  var lastNavCanGoBack = null;

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

  function bytesToBase64(bytes) {
    var binary = '';
    for (var offset = 0; offset < bytes.length; offset += BYTE_STRING_BATCH) {
      var batch = bytes.subarray(offset, offset + BYTE_STRING_BATCH);
      binary += String.fromCharCode.apply(null, batch);
    }
    return btoa(binary);
  }

  function sendNative(payload) {
    return browser.runtime.sendNativeMessage(NATIVE_APP, payload);
  }

  function assertOk(response, errorMessage) {
    if (!response || response.ok !== true) {
      throw new Error(errorMessage);
    }
  }

  function sendBytes(id, bytes) {
    var chain = Promise.resolve();
    for (var offset = 0; offset < bytes.length; offset += CHUNK_BYTES) {
      (function (chunk) {
        chain = chain.then(function () {
          return sendNative({
            type: 'appendDownloadChunk',
            id: id,
            data: bytesToBase64(chunk)
          }).then(function (response) {
            assertOk(response, 'Native download chunk rejected');
          });
        });
      })(bytes.subarray(offset, offset + CHUNK_BYTES));
    }
    return chain;
  }

  function readBlobSlice(blob, start, end) {
    return new Promise(function (resolve, reject) {
      var reader = new FileReader();
      reader.onload = function () {
        resolve(reader.result);
      };
      reader.onerror = function () {
        reject(reader.error || new Error('FileReader failed'));
      };
      reader.onabort = function () {
        reject(new Error('FileReader aborted'));
      };
      reader.readAsArrayBuffer(blob.slice(start, end));
    });
  }

  function sendBlobChunks(id, blob) {
    if (blob.stream && typeof ReadableStream !== 'undefined') {
      var reader = blob.stream().getReader();
      function pump() {
        return reader.read().then(function (result) {
          if (result.done) return;
          var next = result.value ? sendBytes(id, result.value) : Promise.resolve();
          return next.then(pump);
        });
      }
      return pump();
    }

    var offset = 0;
    function readNext() {
      if (offset >= blob.size) return Promise.resolve();
      var end = Math.min(offset + CHUNK_BYTES, blob.size);
      return readBlobSlice(blob, offset, end).then(function (buffer) {
        offset = end;
        return sendBytes(id, new Uint8Array(buffer)).then(readNext);
      });
    }
    return readNext();
  }

  function sendPageDownload(pageUrl, fileName) {
    var id = nextDownloadId();
    var started = false;
    return fetch(pageUrl)
      .then(function (response) {
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
        }
        console.error('Sevpor GeckoView download failed', error);
      });
  }

  function fileNameFromAnchor(anchor) {
    var name = anchor.getAttribute('download') || '';
    return name || 'download';
  }

  document.addEventListener(
    'click',
    function (event) {
      var target = event.target;
      if (!target || !target.closest) return;
      var anchor =
        target.closest('a[href^="blob:"]') ||
        target.closest('a[href^="data:"][download]');
      if (!anchor) return;
      event.preventDefault();
      sendPageDownload(anchor.href, fileNameFromAnchor(anchor));
    },
    true
  );

  if (window === window.top) {
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
