// Configuração de RUNTIME do app web (não embutida no bundle — spec §3.1).
// Em produção este arquivo é substituído para apontar à API e ao site reais (permite trocar o
// endereço em manutenção/failover sem rebuild).
window.NINER_API_BASE = 'http://localhost:8080';
window.NINER_SITE_BASE = 'http://localhost:5175';
