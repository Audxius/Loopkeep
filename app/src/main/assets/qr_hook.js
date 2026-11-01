// Runs at document start on tiktok.com: when the QR login page asks TikTok for a code, passes the
// link inside the code to Loopkeep, which opens it in the (already signed-in) TikTok app.
(function () {
  if (window.__loopkeepQr) return;
  window.__loopkeepQr = true;
  const report = text => {
    try {
      const url = JSON.parse(text).data.qrcode_index_url;
      if (url) window.LoopkeepSync.qr(url);
    } catch (e) {}
  };
  const isQr = u => String(u).indexOf('/passport/web/get_qrcode/') >= 0;
  const origFetch = window.fetch;
  window.fetch = function (input, init) {
    const p = origFetch.apply(this, arguments);
    try {
      if (isQr(input && input.url || input)) p.then(r => r.clone().text()).then(report).catch(() => {});
    } catch (e) {}
    return p;
  };
  const open = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function (method, url) {
    this.__loopkeepUrl = url;
    return open.apply(this, arguments);
  };
  const send = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.send = function () {
    if (isQr(this.__loopkeepUrl)) this.addEventListener('load', () => report(this.responseText));
    return send.apply(this, arguments);
  };
})();
