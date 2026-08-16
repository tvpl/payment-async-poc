# Fluxo local de artefatos

Dependências cross-boundary permanecem declaradas por GAV. O caminho padrão publica em um repositório Maven descartável sob `build/` e compila um consumer sem substitution:

```bash
scripts/artifacts/verify-artifact-only.sh
```

O fixture reserva `com.example.platform` exclusivamente ao repositório informado. Maven Central resolve apenas dependências de terceiros. Se o artefato ou a versão estiver ausente, o build falha sem procurar fontes em outra raiz.

Composite substitution não é mais possível neste workspace: a migração removeu o build agregador da raiz (não há `settings.gradle`, `build.gradle` nem `gradlew` na raiz), então não existe build para passar a `--include-build`. O script `run-with-composite.sh`, que existia para demonstrar o contraste, foi aposentado junto com o agregador — o invariante que ele provava virou propriedade estrutural, coberta por `test_artifact_flow.py::test_no_root_gradle_build_exists_to_include`.
