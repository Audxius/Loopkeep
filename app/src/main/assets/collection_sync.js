// Runs inside tiktok.com in Loopkeep's WebView, with the user's own login. Lists the account's
// collections and the post IDs in each, and the account's own posts (private ones included, since
// the owner is asking), through the same web API (and request signing) TikTok's page uses itself,
// and hands the result to the app. Reads only; changes nothing on TikTok.
(async () => {
  const bridge = window.LoopkeepSync;
  const handle = '__HANDLE__';
  const sleep = ms => new Promise(r => setTimeout(r, ms));

  function cookie(name) {
    const m = document.cookie.match(new RegExp('(?:^|; )' + name + '=([^;]+)'));
    return m ? m[1] : '';
  }

  function sign(url) {
    try {
      const s = window.byted_acrawler && window.byted_acrawler.frontierSign && window.byted_acrawler.frontierSign(url);
      if (s && s['X-Bogus']) return url + '&X-Bogus=' + encodeURIComponent(s['X-Bogus']);
    } catch (e) {}
    return url;
  }

  // Plain request first (the page's own fetch hook may sign it); signed again if TikTok refuses.
  async function get(path, params) {
    const query = new URLSearchParams(Object.assign({
      aid: '1988', app_name: 'tiktok_web', device_platform: 'web_pc', msToken: cookie('msToken'),
    }, params)).toString();
    const url = location.origin + path + '?' + query;
    let last = '';
    for (const u of [url, sign(url)]) {
      const r = await fetch(u, { credentials: 'include' });
      last = await r.text();
      if (!last) continue;
      const json = JSON.parse(last);
      if ((json.statusCode ?? json.status_code ?? 0) === 0) return json;
      last = 'statusCode ' + (json.statusCode ?? json.status_code);
    }
    throw new Error('TikTok refused ' + path + (last ? ' (' + last.slice(0, 80) + ')' : ' (empty answer)'));
  }

  function scope() {
    const el = document.getElementById('__UNIVERSAL_DATA_FOR_REHYDRATION__');
    try { return JSON.parse(el ? el.textContent : '{}').__DEFAULT_SCOPE__ || {}; } catch (e) { return {}; }
  }

  // Every file link TikTok gives for the post (player, download, each quality), best guess first. Slideshows have none.
  function videoUrls(item) {
    if (item.imagePost) return [];
    const v = item.video || {};
    const fromBitrates = (v.bitrateInfo || []).flatMap(b => ((b && b.PlayAddr) || {}).UrlList || []);
    return [v.playAddr, v.downloadAddr, ...fromBitrates].filter((u, i, all) => typeof u === 'string' && u.startsWith('https://') && all.indexOf(u) === i);
  }

  function base64(bytes) {
    let s = '';
    for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    return btoa(s);
  }

  // Fetches the file with the page's own session (falling back to no credentials if the CDN's CORS
  // rules refuse them) and streams it to the app. Returns 'ok' or throws with the reason.
  async function grab(p) {
    let r = null, why = '';
    attempts:
    for (const url of p.urls) {
      for (const credentials of ['include', 'omit']) {
        try {
          r = await fetch(url, { credentials, referrer: location.origin + '/' });
          if (r.ok) break attempts;
          why = 'HTTP ' + r.status;
        } catch (e) {
          why = String(e && e.message || e);
        }
        r = null;
      }
    }
    if (!r) throw new Error(why || 'no answer');
    const bytes = new Uint8Array(await r.arrayBuffer());
    if (bytes.length < 10000) throw new Error('only ' + bytes.length + ' bytes');
    bridge.fileStart(p.id);
    const chunk = 768 * 1024;
    for (let i = 0; i < bytes.length; i += chunk) {
      if (!bridge.fileChunk(p.id, base64(bytes.subarray(i, i + chunk)))) throw new Error('the app could not store it');
    }
    const err = bridge.fileEnd(p.id);
    if (err) throw new Error(err);
    return 'ok';
  }

  try {
    const me = (scope()['webapp.app-context'] || {}).user || {};
    let secUid = me.secUid;
    let uniqueId = me.uniqueId || handle;
    // How many posts the profile says it has, to check the listing found them all.
    let expected = 0;
    if (uniqueId) {
      // The profile page carries the account's secUid and post count in its embedded data.
      try {
        const html = await (await fetch('/@' + encodeURIComponent(uniqueId), { credentials: 'include' })).text();
        const m = html.match(/<script id="__UNIVERSAL_DATA_FOR_REHYDRATION__"[^>]*>([\s\S]*?)<\/script>/);
        const info = JSON.parse(m[1]).__DEFAULT_SCOPE__['webapp.user-detail'].userInfo;
        secUid = secUid || info.user.secUid;
        uniqueId = info.user.uniqueId || uniqueId;
        expected = Number((info.stats || info.statsV2 || {}).videoCount) || 0;
      } catch (e) {}
    }
    if (!secUid) throw new Error(uniqueId ? 'Could not find the account @' + uniqueId : 'Could not tell which account is signed in');

    // Own posts first: a failure here shouldn't cost the collections, and vice versa.
    const own = [];
    let ownError = '';
    function add(item) {
      if (!item || !item.id || own.some(p => p.id === String(item.id))) return;
      const urls = videoUrls(item);
      own.push({
        id: String(item.id),
        caption: item.desc || '',
        createTime: Number(item.createTime) || 0,
        videoUrl: urls[0] || '',
        urls,
        private: !!item.privateItem,
      });
    }
    // TikTok's list sometimes leaves a post out (pinned, just posted, or at a page boundary), so it is
    // read newest first and, if posts are still missing, oldest first, and the two are merged.
    for (const order of ['0', '2']) {
      try {
        for (let cursor = '0', page = 0; page < 200; page++) {
          const r = await get('/api/post/item_list/', {
            secUid, count: '35', cursor, coverFormat: '2', needPinnedItemIds: 'true', post_item_list_request_type: order,
          });
          for (const item of r.itemList || []) add(item);
          bridge.progress('Your videos: ' + own.length + (expected ? ' of ' + expected : '') + ' (' + own.filter(p => p.private).length + ' Only me)');
          if (!r.hasMore || !(r.itemList || []).length) break;
          cursor = String(r.cursor);
          await sleep(400);
        }
      } catch (e) {
        ownError = String(e && e.message || e);
      }
      if (expected && own.length >= expected) break;
      await sleep(400);
    }
    if (own.length) ownError = '';

    // Download your own videos the app doesn't have yet right here, inside your signed-in TikTok page,
    // so TikTok sees exactly the browser it handed the file link to. Each file is passed to the app
    // in base64 chunks. Whatever fails here is reported per video and still goes through the queue.
    const needed = own.filter(p => p.videoUrl && bridge.needs(p.id));
    let got = 0;
    for (const [i, p] of needed.entries()) {
      bridge.progress('Downloading your videos: ' + (i + 1) + ' / ' + needed.length);
      p.file = await grab(p).catch(e => String(e && e.message || e));
      if (p.file === 'ok') got++;
      await sleep(300);
    }
    if (needed.length) bridge.progress('Downloaded ' + got + ' of ' + needed.length + ' of your videos');

    const collections = [];
    for (let cursor = '0', page = 0; page < 50; page++) {
      const r = await get('/api/user/collection_list/', { secUid, count: '30', cursor });
      for (const c of r.collectionList || []) collections.push({ id: String(c.collectionId), name: c.name, total: c.total || 0 });
      if (!r.hasMore) break;
      cursor = String(r.cursor);
      await sleep(400);
    }
    bridge.progress('Found ' + collections.length + ' collections');

    const out = [];
    for (const [i, c] of collections.entries()) {
      const ids = [];
      for (let cursor = '0', page = 0; page < 200; page++) {
        const r = await get('/api/collection/item_list/', { collectionId: c.id, count: '30', cursor, sourceType: '113' });
        for (const item of r.itemList || []) if (item && item.id) ids.push(String(item.id));
        if (!r.hasMore) break;
        cursor = String(r.cursor);
        await sleep(400);
      }
      out.push({ name: c.name, ids });
      bridge.progress((i + 1) + ' / ' + collections.length + ': ' + c.name + ' (' + ids.length + ')');
      await sleep(400);
    }
    bridge.done(JSON.stringify({ handle: uniqueId || '', collections: out, own, ownError, expected }));
  } catch (e) {
    bridge.fail(String(e && e.message || e));
  }
})();
