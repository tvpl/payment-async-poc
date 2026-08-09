# Operação e Registry

## Publicação local

```bash
./gradlew build publishAllToLocalBuildRepository verifyLocalPublication --no-daemon
scripts/verify-consumer-fixture.sh
```

Os dois artefatos são gravados em `build/repository` com POM, JAR, sources e Javadoc. O fixture reserva `com.example.payments` exclusivamente para esse repositório; ausência ou divergência de coordenadas falha.

## Evolução e dry run

1. Altere o `.avsc` atual e o manifest na mesma mudança.
2. Execute `./gradlew checkSchemaCompatibility --no-daemon`.
3. Se compatível, acrescente o estado publicado em novo diretório de histórico.
4. Publique localmente e execute o fixture.
5. Registre no Registry somente em etapa externa autorizada.

O dry run não chama nem modifica o Registry. A API de registro depende do ambiente escolhido; esta fronteira não mantém comando com credencial embutida.

## Mudança incompatível

Não substitua o artifact anterior. Crie major, artifact id e tópico novos, mantenha ambos durante a migração e registre ADR. Rollback direciona consumidores ao artifact/tópico anterior; histórico não é apagado.

## Troubleshooting

- `Avro codec pool exhausted`: revise capacidade, timeout e concorrência do consumidor; não aumente o pool sem medir.
- `missing published artifact`: execute a publicação local e confirme versão/GAV.
- incompatibilidade: leia a descrição emitida pelo Avro; não atualize o histórico para esconder o erro.
- Registry externo indisponível: o gate local continua válido, mas integração permanece `NOT_RUN` até ambiente disponível.
