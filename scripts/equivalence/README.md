# Gate de equivalência

Este diretório congela o inventário anterior à segregação. O gate cobre fontes Java, testes, migrations Flyway, schemas Avro, tópicos, dashboards, scripts operacionais e documentos centrais. Mudança, perda, adição não registrada ou chave lógica duplicada falha com diagnóstico.

O arquivo `baseline-evidence.json` registra separadamente o estado dos gates no momento da captura. Uma falha preexistente deve ser registrada ali antes de qualquer move. Ela não pode ser atribuída à migração.

## Uso

```bash
python3 scripts/equivalence/equivalence.py verify \
  --root . \
  --manifest scripts/equivalence/baseline-manifest.json

python3 -m unittest discover -s scripts/equivalence -p 'test_*.py'
```

Para revisar uma mudança intencional, gere o candidato na saída padrão, inspecione o diff e altere o manifest no mesmo commit que prova a realocação:

```bash
python3 scripts/equivalence/equivalence.py generate --root . \
  --output /tmp/payment-async-baseline-candidate.json
```

O gate não edita o workspace e não remove mudanças locais.
