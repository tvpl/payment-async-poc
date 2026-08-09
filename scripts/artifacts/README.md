# Fluxo local de artefatos

Dependências cross-boundary permanecem declaradas por GAV. O caminho padrão publica em um repositório Maven descartável sob `build/` e compila um consumer sem substitution:

```bash
scripts/artifacts/verify-artifact-only.sh
```

O fixture reserva `com.example.platform` exclusivamente ao repositório informado. Maven Central resolve apenas dependências de terceiros. Se o artefato ou a versão estiver ausente, o build falha sem procurar fontes em outra raiz.

Composite é conveniência opt-in:

```bash
scripts/artifacts/run-with-composite.sh
```

Esse comando mantém o GAV no consumer e adiciona `--include-build` explicitamente. Gates de release e `verify-artifact-only.sh` nunca usam composite substitution.
