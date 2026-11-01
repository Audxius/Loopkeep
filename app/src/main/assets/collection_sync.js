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

  // The video file TikTok's own player would play. Slideshows have none.
  function videoUrl(item) {
    if (item.imagePost) return '';
    const v = item.video || {};
    const fromList = list => (list && list.length && (list[0].PlayAddr || {}).UrlList || [])[0];
    return v.playAddr || fromList(v.bitrateInfo) || v.downloadAddr || '';
  }

  try {
    const me = (scope()['webapp.app-context'] || {}).user || {};
    let secUid = me.secUid;
    let uniqueId = me.uniqueId || handle;
    if (!secUid) {
      if (!uniqueId) throw new Error('Could not tell which account is signed in');
      // The profile page carries the account's secUid in its embedded data.
      const html = await (await fetch('/@' + encodeURIComponent(uniqueId), { credentials: 'include' })).text();
      const m = html.match(/<script id="__UNIVERSAL_DATA_FOR_REHYDRATION__"[^>]*>([\s\S]*?)<\/script>/);
      try {
        const user = JSON.parse(m[1]).__DEFAULT_SCOPE__['webapp.user-detail'].userInfo.user;
        secUid = user.secUid;
        uniqueId = user.uniqueId || uniqueId;
      } catch (e) {}
      if (!secUid) throw new Error('Could not find the account @' + uniqueId);
    }

    // Own posts first: a failure here shouldn't cost the collections, and vice versa.
    const own = [];
    let ownError = '';
    try {
      for (let cursor = '0', page = 0; page < 200; page++) {
        const r = await get('/api/post/item_list/', { secUid, count: '35', cursor, coverFormat: '2' });
        for (const item of r.itemList || []) {
          if (!item || !item.id) continue;
          own.push({
            id: String(item.id),
            caption: item.desc || '',
            createTime: Number(item.createTime) || 0,
            videoUrl: videoUrl(item),
            private: !!item.privateItem,
          });
        }
        bridge.progress('Your videos: ' + own.length);
        if (!r.hasMore) break;
        cursor = String(r.cursor);
        await sleep(400);
      }
    } catch (e) {
      ownError = String(e && e.message || e);
    }

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
    bridge.done(JSON.stringify({ handle: uniqueId || '', collections: out, own, ownError }));
  } catch (e) {
    bridge.fail(String(e && e.message || e));
  }
})();
