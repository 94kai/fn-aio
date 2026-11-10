(function(key, maxBytes, chunkBytes) {
  'use strict';
  if (window[key]) return;
  // A download navigation may leave the old document visible. Replace, never stack, capture hooks.
  if (window.__aioDownloadCapture && typeof window.__aioDownloadCapture.dispose === 'function') window.__aioDownloadCapture.dispose();
  const blobs = new Map();
  const create = URL.createObjectURL;
  const revoke = URL.revokeObjectURL;
  let task = null;
  let nextId = 0;
  const wrappedCreate = function(object) {
    const url = create.call(URL, object);
    if (object instanceof Blob && object.size <= maxBytes) {
      if (blobs.size >= 16) blobs.delete(blobs.keys().next().value);
      blobs.set(url, object);
    }
    return url;
  };
  const wrappedRevoke = function(url) {
    // a.click(); revokeObjectURL() must not invalidate a user download before native polling.
    setTimeout(() => blobs.delete(url), 10000);
    return revoke.call(URL, url);
  };
  URL.createObjectURL = wrappedCreate;
  URL.revokeObjectURL = wrappedRevoke;
  function allowed(url) {
    if (typeof url !== 'string') return false;
    if (url.startsWith('data:')) return url.length <= 8 * 1024 * 1024;
    if (!url.startsWith('blob:')) return false;
    try { return new URL(url).origin === location.origin; } catch { return false; }
  }
  function prepare(url, name) {
    if (task || !allowed(url)) return false;
    const current = {id: String(++nextId), name: String(name || '下载文件.bin').slice(0, 200), state: 'preparing', blob: null, size: 0, mime: '', chunk: null, reading: false};
    task = current;
    setTimeout(() => { if (task === current && current.state === 'preparing') current.state = 'error'; }, 15000);
    const cached = blobs.get(url);
    const source = cached ? Promise.resolve(cached) : fetch(url).then(response => {
      if (!response.ok) throw new Error('download unavailable');
      return response.blob();
    });
    source.then(blob => {
      if (task !== current || current.state !== 'preparing') return;
      if (blob.size > maxBytes) throw new Error('download too large');
      current.blob = blob;
      current.size = blob.size;
      current.mime = blob.type;
      current.state = 'ready';
    }).catch(() => { if (task === current) current.state = 'error'; });
    return true;
  }
  function info() {
    return task ? {id: task.id, name: task.name, size: task.size, mime: task.mime, state: task.state} : null;
  }
  function read(id, offset) {
    const current = task;
    if (!current || current.id !== id || current.state !== 'ready' || current.reading || current.chunk || !Number.isSafeInteger(offset) || offset < 0 || offset >= current.size) return false;
    current.reading = true;
    const part = current.blob.slice(offset, Math.min(offset + chunkBytes, current.size));
    const reader = new FileReader();
    reader.onload = () => {
      if (task !== current) return;
      current.reading = false;
      current.chunk = {offset, data: String(reader.result).split(',')[1] || ''};
    };
    reader.onerror = () => { if (task === current) { current.reading = false; current.state = 'error'; } };
    reader.readAsDataURL(part);
    return true;
  }
  function take(id) {
    if (!task || task.id !== id) return null;
    if (task.state === 'error') return {error: true};
    const chunk = task.chunk;
    task.chunk = null;
    return chunk;
  }
  function release(id) { if (task && task.id === id) task = null; }
  function click(event) {
    const anchor = event.target && event.target.closest ? event.target.closest('a[href]') : null;
    if (!anchor || !allowed(anchor.href)) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    prepare(anchor.href, anchor.download || '下载文件.bin');
  }
  function dispose() {
    document.removeEventListener('click', click, true);
    if (URL.createObjectURL === wrappedCreate) URL.createObjectURL = create;
    if (URL.revokeObjectURL === wrappedRevoke) URL.revokeObjectURL = revoke;
    task = null;
    blobs.clear();
    delete window[key];
    if (window.__aioDownloadCapture === api) delete window.__aioDownloadCapture;
  }
  const api = {prepare, info, read, take, release, dispose};
  document.addEventListener('click', click, true);
  Object.defineProperty(window, key, {value: api, configurable: true});
  Object.defineProperty(window, '__aioDownloadCapture', {value: api, configurable: true});
})
