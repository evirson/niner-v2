// Configuração de RUNTIME do site (não é embutida no bundle — spec §3.1).
// Em produção, este arquivo é substituído para apontar à API real; permite trocar o
// endereço da API (manutenção/failover) sem rebuild do site.
window.NINER_API_BASE = 'http://localhost:8080';
// Endereço do app do ERP (web), para onde o cliente vai após o signup.
window.NINER_WEB_BASE = 'http://localhost:5173';
