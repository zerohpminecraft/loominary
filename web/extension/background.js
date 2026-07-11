/**
 * Background event page (Manifest V2 — works in Firefox and Chrome).
 *
 * Responsibilities:
 *   1. Inject content.js when the browser-action icon is clicked.
 *   2. Fetch image URLs on behalf of the content script.
 *
 * Because this background page runs under the extension origin and the manifest
 * declares "<all_urls>" in permissions, fetch() here is not subject to CORS —
 * the content script can request any image URL and get its raw bytes back as a
 * base64 data-URI, which it can then canvas without any taint.
 */

// Inject (or re-inject) content.js when the toolbar icon is clicked.
chrome.browserAction.onClicked.addListener(function (tab) {
  // Remove any existing overlay first.
  chrome.tabs.executeScript(tab.id, {
    code: 'var e = document.getElementById("_mcpal_overlay"); if (e) e.remove();',
  }, function () {
    chrome.tabs.executeScript(tab.id, { file: 'content.js' });
  });
});

// Handle image-fetch requests from the content script.
chrome.runtime.onMessage.addListener(function (msg, _sender, sendResponse) {
  if (msg.type !== 'MCPAL_FETCH') return;

  fetch(msg.url, { credentials: 'omit' })
    .then(function (r) {
      if (!r.ok) throw new Error('HTTP ' + r.status);
      var ct = (r.headers.get('content-type') || 'image/jpeg').split(';')[0].trim();
      return r.arrayBuffer().then(function (buf) {
        var bytes = new Uint8Array(buf);
        var bin = '';
        var chunk = 8192;
        for (var i = 0; i < bytes.length; i += chunk) {
          bin += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
        }
        sendResponse({ ok: true, dataUrl: 'data:' + ct + ';base64,' + btoa(bin) });
      });
    })
    .catch(function (e) {
      sendResponse({ ok: false, error: String(e) });
    });

  return true; // keep the message channel open for the async response
});
