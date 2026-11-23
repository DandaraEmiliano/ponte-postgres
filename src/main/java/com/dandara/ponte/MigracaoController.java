package com.dandara.ponte;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
class MigracaoController {
    private final MigracaoService migracao;

    MigracaoController(MigracaoService migracao) {
        this.migracao = migracao;
    }

    @GetMapping("/migracao")
    MigracaoService.Painel status() {
        return migracao.painel();
    }

    @PostMapping("/migracao/iniciar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void iniciar() {
        migracao.iniciar();
    }

    @PostMapping("/migracao/pausar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void pausar() {
        migracao.pausar(true);
    }

    @PostMapping("/migracao/retomar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void retomar() {
        migracao.pausar(false);
    }

    @PostMapping("/migracao/conferir")
    MigracaoService.Conferencia conferir() {
        return migracao.conferir();
    }

    @PostMapping("/migracao/ativar-destino")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void ativarDestino() {
        migracao.leituraNoDestino(true);
    }

    @PostMapping("/migracao/voltar-origem")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void voltarOrigem() {
        migracao.leituraNoDestino(false);
    }

    @PostMapping("/migracao/reiniciar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reiniciar() {
        migracao.reiniciar();
    }

    @GetMapping("/clientes")
    MigracaoService.Leitura listar() {
        return migracao.lerClientes();
    }

    @GetMapping("/origem/clientes")
    List<MigracaoService.Cliente> listarOrigem() {
        return migracao.clientesOrigem();
    }

    @PostMapping("/origem/clientes")
    @ResponseStatus(HttpStatus.CREATED)
    MigracaoService.Cliente criar(@RequestBody Cadastro cadastro) {
        return salvar(null, cadastro);
    }

    @PutMapping("/origem/clientes/{id}")
    MigracaoService.Cliente editar(@PathVariable long id, @RequestBody Cadastro cadastro) {
        return salvar(id, cadastro);
    }

    @DeleteMapping("/origem/clientes/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void excluir(@PathVariable long id) {
        migracao.excluir(id);
    }

    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail erro(ResponseStatusException ex) {
        return ex.getBody();
    }

    private MigracaoService.Cliente salvar(Long id, Cadastro cadastro) {
        var nome = cadastro.nome() == null ? "" : cadastro.nome().strip();
        var email = cadastro.email() == null ? "" : cadastro.email().strip();
        if (nome.isEmpty() || nome.length() > 100 || email.length() > 254 || !email.matches("[^\\s@]+@[^\\s@]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe nome e e-mail válidos.");
        }
        return migracao.gravar(id, nome, email);
    }

    record Cadastro(String nome, String email) {}
}
