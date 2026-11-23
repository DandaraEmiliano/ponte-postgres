CREATE TABLE IF NOT EXISTS clientes (
    id bigint PRIMARY KEY,
    nome text,
    email text,
    versao bigint NOT NULL,
    excluido boolean NOT NULL
)^^^
CREATE TABLE IF NOT EXISTS migracao (
    id integer PRIMARY KEY CHECK (id = 1),
    fase text NOT NULL DEFAULT 'IDLE',
    copia_cursor bigint NOT NULL DEFAULT 0,
    copia_limite bigint NOT NULL DEFAULT 0,
    copia_total bigint NOT NULL DEFAULT 0,
    copiados bigint NOT NULL DEFAULT 0,
    aplicado bigint NOT NULL DEFAULT 0,
    pausado boolean NOT NULL DEFAULT false,
    destino_ativo boolean NOT NULL DEFAULT false,
    conferido bigint
)^^^
INSERT INTO migracao (id) VALUES (1) ON CONFLICT DO NOTHING^^^
CREATE TABLE IF NOT EXISTS atividade (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    categoria text NOT NULL,
    mensagem text NOT NULL,
    ocorrido_em timestamptz NOT NULL DEFAULT clock_timestamp()
)^^^
