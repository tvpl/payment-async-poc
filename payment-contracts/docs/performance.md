# Performance

O adapter limita serializers/deserializers não thread-safe por pool fixo. Virtual threads compartilham essa capacidade; nenhuma requisição cria client próprio.

Dimensionamento parte de três valores medidos no consumidor:

1. concorrência máxima de serialização/deserialização;
2. tempo de serviço do codec, incluindo Registry quando houver lookup;
3. orçamento de aquisição antes de retry ou backpressure.

O default `8` é apenas bootstrap de migração, não certificação universal. Aumentar capacidade também aumenta clients, conexões e memória. O consumidor registra versão, pool, timeout, throughput, percentis e timeouts em seu relatório de carga.

O teste local executa 100 operações em virtual threads e prova limite de criação, não throughput de produção. Certificação do fluxo completo pertence aos serviços e ao gate cross-boundary.
