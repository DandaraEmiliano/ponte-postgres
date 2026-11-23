package com.dandara.ponte;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

final class MigracaoService {
    private static final Logger LOG = LoggerFactory.getLogger(MigracaoService.class);
    private static final int LOTE_COPIA = 2;
    private static final int LOTE_MUDANCAS = 10;

    private static final RowMapper<Cliente> CLIENTE = (rs, row) -> new Cliente(
        rs.getLong("id"), rs.getString("nome"), rs.getString("email"), rs.getLong("versao"));
    private static final RowMapper<Mudanca> MUDANCA = (rs, row) -> new Mudanca(
        CLIENTE.mapRow(rs, row), rs.getBoolean("excluido"));
    private static final RowMapper<Estado> ESTADO = (rs, row) -> new Estado(
        rs.getString("fase"), rs.getLong("copia_cursor"), rs.getLong("copia_limite"),
        rs.getLong("copia_total"), rs.getLong("copiados"), rs.getLong("aplicado"),
        rs.getBoolean("pausado"), rs.getBoolean("destino_ativo"), rs.getObject("conferido", Long.class));

    private final JdbcTemplate origem;
    private final JdbcTemplate destino;
    private final TransactionTemplate txOrigem;
    private final TransactionTemplate txDestino;
    private volatile boolean workerComErro;

    MigracaoService(DataSource origem, DataSource destino) {
        this.origem = new JdbcTemplate(origem);
        this.destino = new JdbcTemplate(destino);
        txOrigem = new TransactionTemplate(new DataSourceTransactionManager(origem));
        txDestino = new TransactionTemplate(new DataSourceTransactionManager(destino));
    }

    record Cliente(long id, String nome, String email, long versao) {}
    record Mudanca(Cliente cliente, boolean excluido) {}
    record Estado(String fase, long cursor, long limite, long total, long copiados,
                  long aplicado, boolean pausado, boolean destinoAtivo, Long conferido) {}
    record Log(long atual, long pendentes, long atrasoSegundos) {}
    record Atividade(String categoria, String mensagem, OffsetDateTime quando) {}
    record Painel(Estado estado, Log log, long clientesOrigem, long clientesDestino,
                  String leituraEfetiva, boolean podeVirar, boolean workerComErro,
                  List<Atividade> atividade) {}
    record Divergencia(long id, Cliente origem, Cliente destino) {}
    record Conferencia(long posicaoOrigem, long posicaoDestino, boolean completa,
                       int totalDivergencias, List<Divergencia> divergencias) {}
    record Leitura(String banco, List<Cliente> clientes) {}

    void iniciar() {
        comEstado(estado -> {
            if (!estado.fase().equals("IDLE")) throw conflito("Já existe uma migração em andamento.");
            naMesmaPosicao(() -> {
                var limite = origem.queryForObject("SELECT coalesce(max(id), 0) FROM clientes", Long.class);
                var total = contar(origem, "SELECT count(*) FROM clientes");
                var posicao = posicaoOrigem();
                destino.update("""
                    UPDATE migracao SET fase = 'COPY', copia_limite = ?, copia_total = ?, aplicado = ?
                    WHERE id = 1
                    """, limite, total, posicao);
                atividade("COPIA", "Cópia iniciada na posição " + posicao + ".");
                return null;
            });
            return null;
        });
    }

    void pausar(boolean pausado) {
        comEstado(estado -> {
            if (estado.fase().equals("IDLE")) throw conflito("Inicie uma migração primeiro.");
            if (estado.pausado() != pausado) {
                destino.update("UPDATE migracao SET pausado = ? WHERE id = 1", pausado);
                atividade("REPLICACAO", pausado ? "Aplicação de mudanças pausada." : "Aplicação de mudanças retomada.");
            }
            return null;
        });
    }

    void reiniciar() {
        comEstado(estado -> {
            destino.update("""
                UPDATE migracao SET fase = 'IDLE', copia_cursor = 0, copia_limite = 0,
                    copia_total = 0, copiados = 0, aplicado = 0, pausado = false,
                    destino_ativo = false, conferido = NULL WHERE id = 1
                """);
            destino.update("DELETE FROM clientes");
            destino.update("DELETE FROM atividade");
            atividade("SISTEMA", "Destino limpo. Os dados na origem foram preservados.");
            return null;
        });
    }

    @Scheduled(fixedDelayString = "${ponte.worker.delay}")
    void ciclo() {
        try {
            comEstado(estado -> {
                if (estado.fase().equals("COPY")) copiarLote(estado);
                if (estado.fase().equals("REPLAY") && !estado.pausado()) replicarLote(estado);
                return null;
            });
            workerComErro = false;
        } catch (RuntimeException erro) {
            if (!workerComErro) LOG.warn("Ciclo interrompido. O checkpoint será retomado.", erro);
            workerComErro = true;
        }
    }

    private void copiarLote(Estado estado) {
        var lote = origem.query("""
            SELECT id, nome, email, versao FROM clientes WHERE id > ? AND id <= ?
            ORDER BY id LIMIT ?
            """, CLIENTE, estado.cursor(), estado.limite(), LOTE_COPIA);
        for (var cliente : lote) aplicar(new Mudanca(cliente, false));
        var cursor = lote.isEmpty() ? estado.cursor() : lote.get(lote.size() - 1).id();
        var concluida = lote.size() < LOTE_COPIA || cursor >= estado.limite();
        destino.update("""
            UPDATE migracao SET copia_cursor = ?, copiados = copiados + ?, fase = ? WHERE id = 1
            """, cursor, lote.size(), concluida ? "REPLAY" : "COPY");
        if (concluida) atividade("COPIA", "Cópia encerrada. Mudanças posteriores podem ser aplicadas.");
    }

    private void replicarLote(Estado estado) {
        var lote = origem.query("""
            SELECT id, nome, email, versao, excluido FROM mudancas
            WHERE versao > ? ORDER BY versao LIMIT ?
            """, MUDANCA, estado.aplicado(), LOTE_MUDANCAS);
        if (lote.isEmpty()) return;
        for (var mudanca : lote) aplicar(mudanca);
        var posicao = lote.get(lote.size() - 1).cliente().versao();
        destino.update("UPDATE migracao SET aplicado = ? WHERE id = 1", posicao);
        atividade("REPLICACAO", lote.size() + " mudança(s) aplicadas até a posição " + posicao + ".");
    }

    private void aplicar(Mudanca mudanca) {
        var cliente = mudanca.cliente();
        destino.update("""
            INSERT INTO clientes (id, nome, email, versao, excluido) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET nome = excluded.nome, email = excluded.email,
                versao = excluded.versao, excluido = excluded.excluido
            WHERE clientes.versao < excluded.versao
            """, cliente.id(), cliente.nome(), cliente.email(), cliente.versao(), mudanca.excluido());
    }

    Painel painel() {
        return comEstado(estado -> {
            var log = origem.queryForObject("""
                SELECT (SELECT coalesce(max(versao), 0) FROM mudancas) AS atual,
                       count(*) AS pendentes,
                       coalesce(floor(extract(epoch FROM (clock_timestamp() - min(registrado_em)))), 0) AS atraso
                FROM mudancas WHERE versao > ?
                """, (rs, row) -> new Log(rs.getLong("atual"), rs.getLong("pendentes"), rs.getLong("atraso")),
                estado.aplicado());
            var atividades = destino.query("""
                SELECT categoria, mensagem, ocorrido_em FROM atividade ORDER BY id DESC LIMIT 12
                """, (rs, row) -> new Atividade(rs.getString("categoria"), rs.getString("mensagem"),
                rs.getObject("ocorrido_em", OffsetDateTime.class)));
            var podeVirar = pronta(estado, log.atual()) && !estado.destinoAtivo();
            return new Painel(estado, log, contar(origem, "SELECT count(*) FROM clientes"),
                contar(destino, "SELECT count(*) FROM clientes WHERE NOT excluido"),
                escolherBanco(estado, log.atual()), podeVirar, workerComErro, atividades);
        });
    }

    Conferencia conferir() {
        return comEstado(estado -> naMesmaPosicao(() -> {
            var verificacao = comparar(estado);
            var limpa = verificacao.completa() && verificacao.totalDivergencias() == 0;
            destino.update("UPDATE migracao SET conferido = ? WHERE id = 1",
                limpa ? verificacao.posicaoOrigem() : null);
            atividade("CONFERENCIA", limpa ? "Integridade confirmada na posição " + verificacao.posicaoOrigem() + "."
                : "Conferência encontrou " + verificacao.totalDivergencias() + " diferença(s).");
            return verificacao;
        }));
    }

    void leituraNoDestino(boolean ativar) {
        comEstado(estado -> naMesmaPosicao(() -> {
            if (ativar) {
                if (!pronta(estado, posicaoOrigem())) {
                    throw conflito("A troca exige cópia concluída, replicação ativa e conferência na posição atual.");
                }
                if (comparar(estado).totalDivergencias() > 0) throw conflito("Há divergências entre os bancos.");
            }
            destino.update("UPDATE migracao SET destino_ativo = ? WHERE id = 1", ativar);
            atividade("LEITURA", ativar ? "Destino habilitado para atender leituras." : "Leituras voltaram para a origem.");
            return null;
        }));
    }

    Leitura lerClientes() {
        return comEstado(estado -> naMesmaPosicao(() -> {
            var banco = escolherBanco(estado, posicaoOrigem());
            var clientes = banco.equals("DESTINO") ? clientesDestino() : clientesOrigem();
            return new Leitura(banco, clientes);
        }));
    }

    List<Cliente> clientesOrigem() {
        return origem.query("SELECT id, nome, email, versao FROM clientes ORDER BY id", CLIENTE);
    }

    Cliente gravar(Long id, String nome, String email) {
        if (id == null) {
            return origem.queryForObject("""
                INSERT INTO clientes (nome, email) VALUES (?, ?) RETURNING id, nome, email, versao
                """, CLIENTE, nome, email);
        }
        return origem.query("""
            UPDATE clientes SET nome = ?, email = ? WHERE id = ? RETURNING id, nome, email, versao
            """, CLIENTE, nome, email, id).stream().findFirst().orElseThrow(MigracaoService::clienteAusente);
    }

    void excluir(long id) {
        if (origem.update("DELETE FROM clientes WHERE id = ?", id) == 0) throw clienteAusente();
    }

    private Conferencia comparar(Estado estado) {
        var posicao = posicaoOrigem();
        var a = new TreeMap<Long, Cliente>();
        var b = new TreeMap<Long, Cliente>();
        clientesOrigem().forEach(cliente -> a.put(cliente.id(), cliente));
        clientesDestino().forEach(cliente -> b.put(cliente.id(), cliente));
        var ids = new java.util.TreeSet<>(a.keySet());
        ids.addAll(b.keySet());
        var diferencas = ids.stream()
            .filter(id -> !java.util.Objects.equals(a.get(id), b.get(id)))
            .map(id -> new Divergencia(id, a.get(id), b.get(id)))
            .toList();
        var completa = estado.fase().equals("REPLAY") && estado.aplicado() == posicao;
        return new Conferencia(posicao, estado.aplicado(), completa, diferencas.size(),
            diferencas.stream().limit(20).toList());
    }

    private List<Cliente> clientesDestino() {
        return destino.query("SELECT id, nome, email, versao FROM clientes WHERE NOT excluido ORDER BY id", CLIENTE);
    }

    private long posicaoOrigem() {
        return contar(origem, "SELECT coalesce(max(versao), 0) FROM mudancas");
    }

    private long contar(JdbcTemplate banco, String sql) {
        return banco.queryForObject(sql, Long.class);
    }

    private boolean pronta(Estado estado, long posicao) {
        return estado.fase().equals("REPLAY") && !estado.pausado() && estado.aplicado() == posicao
            && estado.conferido() != null && estado.conferido() == posicao;
    }

    private String escolherBanco(Estado estado, long posicao) {
        return estado.destinoAtivo() && estado.aplicado() == posicao ? "DESTINO" : "ORIGEM";
    }

    private void atividade(String categoria, String mensagem) {
        destino.update("INSERT INTO atividade (categoria, mensagem) VALUES (?, ?)", categoria, mensagem);
    }

    private <T> T comEstado(Function<Estado, T> acao) {
        return txDestino.execute(tx -> acao.apply(destino.queryForObject(
            "SELECT * FROM migracao WHERE id = 1 FOR UPDATE", ESTADO)));
    }

    private <T> T naMesmaPosicao(Supplier<T> acao) {
        return txOrigem.execute(tx -> {
            origem.execute("SELECT pg_advisory_xact_lock_shared(42170)");
            return acao.get();
        });
    }

    private static ResponseStatusException conflito(String descricao) {
        return new ResponseStatusException(HttpStatus.CONFLICT, descricao);
    }

    private static ResponseStatusException clienteAusente() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Cliente não encontrado.");
    }
}
