#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8090}/api"

request() {
    local method="$1" path="$2"
    shift 2
    curl --silent --show-error --fail-with-body --max-time 15 -X "$method" "$@" "$BASE_URL$path"
}

check() {
    jq -e "$1" > /dev/null
}

await_status() {
    local predicate="$1" response
    for ((attempt = 0; attempt < 80; attempt++)); do
        response=$(request GET /migracao)
        if check "$predicate" <<< "$response"; then
            return
        fi
        sleep 0.5
    done
    printf 'Estado esperado: %s\nEstado observado: %s\n' "$predicate" "$response" >&2
    return 1
}

create() {
    request POST /origem/clientes --json "{\"nome\":\"$1\",\"email\":\"teste@example.test\"}" | jq -er .id
}

edit() {
    request PUT "/origem/clientes/$1" --json "{\"nome\":\"$2\",\"email\":\"teste@example.test\"}"
}

blocked() {
    test "$(curl --silent --show-error --max-time 15 -X POST -o /dev/null -w '%{http_code}' \
        "$BASE_URL/migracao/ativar-destino")" = "409"
}

a=
b=
started=
cleanup() {
    if [[ -n "$a" ]]; then request DELETE "/origem/clientes/$a" >/dev/null 2>&1 || true; fi
    if [[ -n "$b" ]]; then request DELETE "/origem/clientes/$b" >/dev/null 2>&1 || true; fi
    if [[ -n "$started" ]]; then request POST /migracao/reiniciar >/dev/null; fi
}
trap cleanup EXIT

request GET /migracao | check '.estado.fase == "IDLE"'
a=$(create 'Cliente de teste A')
b=$(create 'Cliente de teste B')
request POST /migracao/iniciar
started=1
request POST /migracao/pausar
request GET /migracao | check '.estado.fase == "COPY"'
edit "$a" 'Versão intermediária' > /dev/null
edit "$a" 'Alterado durante a cópia' > /dev/null
await_status '.estado.fase == "REPLAY" and .estado.pausado'

# A cópia já levou a versão mais recente. O replay dos eventos anteriores é inofensivo.
request POST /migracao/conferir | check '.totalDivergencias == 0 and (.completa | not)'
request DELETE "/origem/clientes/$b"
request POST /migracao/conferir | check '.totalDivergencias == 1 and .divergencias[0].origem == null'
blocked
printf 'OK — exclusão pendente visível e troca bloqueada durante a pausa\n'

request POST /migracao/retomar
await_status '.log.pendentes == 0'
blocked
request POST /migracao/conferir | check '.completa and .totalDivergencias == 0'
request POST /migracao/ativar-destino
request GET /clientes | jq -e --argjson id "$a" --argjson excluido "$b" '
    .banco == "DESTINO"
    and any(.clientes[]; .id == $id and .nome == "Alterado durante a cópia")
    and all(.clientes[]; .id != $excluido)' > /dev/null
printf 'OK — conferência liberou o destino; versão mais recente e exclusão preservadas\n'

request POST /migracao/pausar
edit "$a" 'Alterado após a troca' > /dev/null
request GET /clientes | jq -e --argjson id "$a" '
    .banco == "ORIGEM" and any(.clientes[]; .id == $id and .nome == "Alterado após a troca")' > /dev/null
request POST /migracao/retomar
await_status '.leituraEfetiva == "DESTINO" and .log.pendentes == 0'
request GET /clientes | jq -e --argjson id "$a" '
    .banco == "DESTINO" and any(.clientes[]; .id == $id and .nome == "Alterado após a troca")' > /dev/null
request POST /migracao/voltar-origem
request GET /clientes | check '.banco == "ORIGEM"'
printf 'OK — fallback durante atraso, recuperação e rollback manual confirmados\n'
