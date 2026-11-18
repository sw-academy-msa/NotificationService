// 실제 프런트의 Service Worker에 병합하는 예시입니다. 서버만 구현해도 자동 설치되는 파일은 아닙니다.
self.addEventListener('push', event => {
  event.waitUntil((async () => {
    let payload;
    try { payload = event.data?.json() || {}; } catch { payload = {}; }
    const id = /^[0-9a-f-]{36}$/i.test(payload.notificationId || '') ? payload.notificationId : null;
    // foreground에서도 표시해 silent push가 되지 않도록 합니다.
    await self.registration.showNotification(payload.title || 'STOCKDROP 알림', {
      body: payload.body || '알림함에서 내용을 확인해 주세요.',
      tag: id || 'stockdrop-notification',
      data: { notificationId: id }
    });
  })());
});
self.addEventListener('notificationclick', event => {
  event.notification.close();
  const id = event.notification.data?.notificationId;
  const url = new URL('/', self.location.origin);
  if (id) url.searchParams.set('notificationId', id);
  // 이동 후 프런트가 로그인과 알림 소유권 조회를 거쳐 실제 페이지를 선택합니다.
  event.waitUntil(clients.openWindow(url.href));
});
