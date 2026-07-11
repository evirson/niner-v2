(function () {
  const root = document.documentElement;
  const THEME_KEY = 'nainerTema';

  function getActiveTheme() {
    const current = root.getAttribute('data-theme');
    return current === 'light' ? 'light' : 'dark';
  }

  function applyLabel() {
    const icon = document.getElementById('themeIcon');
    const label = document.getElementById('themeLabel');
    if (!icon || !label) return;
    const isDark = getActiveTheme() === 'dark';
    icon.innerHTML = isDark ? '&#9789;' : '&#9788;';
    label.textContent = isDark ? 'Tema escuro' : 'Tema claro';
  }

  const saved = localStorage.getItem(THEME_KEY);
  root.setAttribute('data-theme', saved === 'light' ? 'light' : 'dark');
  applyLabel();

  const toggle = document.getElementById('themeToggle');
  if (toggle) {
    toggle.addEventListener('click', () => {
      const next = getActiveTheme() === 'dark' ? 'light' : 'dark';
      root.setAttribute('data-theme', next);
      localStorage.setItem(THEME_KEY, next);
      applyLabel();
    });
  }

  const navToggle = document.getElementById('navToggle');
  const navbar = document.getElementById('navbar');
  if (navToggle && navbar) {
    navToggle.addEventListener('click', () => {
      navbar.classList.toggle('menu-open');
    });
  }
})();
