/* The native side transfers a port only to the top-level CAS origin. No password access. */
function installAccountCapture(token) {
  if (location.origin !== 'https://tpass.ahtcm.edu.cn' || window !== top) return;
  const connect = event => {
    if (event.data !== token || !event.ports[0]) return;
    window.removeEventListener('message', connect);
    const port = event.ports[0];
    const report = () => {
      const field = document.querySelector('#un,input[name="username"],input[name="un"]');
      const account = field ? String(field.value || '').trim() : '';
      if (/^[0-9]{8,20}$/.test(account)) port.postMessage(account);
    };
    document.addEventListener('submit', report, true);
    const submit = HTMLFormElement.prototype.submit;
    HTMLFormElement.prototype.submit = function () { report(); return submit.apply(this, arguments); };
  };
  window.addEventListener('message', connect);
}
