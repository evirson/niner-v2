// Configuração de RUNTIME do app web (não embutida no bundle — spec §3.1).
// Em produção este arquivo é substituído para apontar à API real (permite trocar o
// endereço da API em manutenção/failover sem rebuild).
window.NINER_API_BASE = 'http://localhost:8080';
