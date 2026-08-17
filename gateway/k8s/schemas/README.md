# Schemas vendorizados (kubeconform)

`*.json` neste diretório são os JSON Schemas que `gateway/scripts/validate-config.py`
(via `make config`) e o job `gateway` do CI raiz passam para o `kubeconform -strict`
junto com os manifests em `gateway/k8s/{base,overlays}/`. O catálogo público do
kubeconform (`kubernetesjsonschema.dev`) não cobre CRDs do Gateway API nem do Envoy
Gateway — por isso vendorizamos os schemas gerados localmente a partir das CRDs
oficiais (limite já registrado no design da feature).

## Fonte

| CRD | Origem | Versão |
| --- | --- | --- |
| `Gateway`, `HTTPRoute` | [kubernetes-sigs/gateway-api](https://github.com/kubernetes-sigs/gateway-api), canal `standard` | `v1.3.0` |
| `SecurityPolicy`, `BackendTrafficPolicy`, `EnvoyProxy` | [envoyproxy/gateway](https://github.com/envoyproxy/gateway), Helm chart `charts/gateway-helm/crds/generated/` | `v1.2.6` |

As CRDs originais (YAML, OpenAPI v3) ficam em `vendor/`, sem edição — só os `.json`
gerados são consumidos pelo kubeconform.

## Regenerar

`generate-schemas.py` é uma cópia sem alteração de `scripts/openapi2jsonschema.py`
do próprio [kubeconform](https://github.com/yannh/kubeconform) (licença MIT),
vendorizada para não depender de rede na hora do gate. Para atualizar uma CRD:

```bash
# 1. baixe a nova versão da CRD para vendor/, sobrescrevendo o arquivo existente
# 2. regenere os .json a partir de tudo em vendor/
cd gateway/k8s/schemas
python3 generate-schemas.py vendor/*.yaml
```

## Limite conhecido

Se uma CRD nova (por exemplo uma versão futura do Envoy Gateway com um recurso
novo) ainda não tiver schema vendorizado aqui, o kubeconform falha com
"could not find schema for <Kind>" em vez de validar silenciosamente errado — o
gate é estrutural e explícito, não best-effort.
