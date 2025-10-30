# Título: Validação Centralizada de Token no API Gateway com Delegação

**Status:** Aceito (Implementado)

## Contexto
Na arquitetura de microsserviços do sistema, **que segue um padrão em camadas**, múltiplos serviços internos (a camada de negócio) precisam estar protegidos contra acessos não autenticados. Implementar a lógica de validação de token JWT em cada serviço geraria duplicação de código e maior complexidade de manutenção da segurança.

Era necessário definir um ponto de entrada único **(atuando como a camada de borda)** que atuasse como um porteiro do ecossistema, garantindo que nenhuma requisição não autenticada alcançasse os serviços internos.
## Decisão

Foi decidido implementar um **API Gateway** usando **Spring Cloud Gateway** para atuar como a camada de segurança de borda.

A autenticação é tratada da seguinte forma:
1. **Rotas Públicas e Protegidas:** O arquivo `application.yml` define explicitamente quais rotas são públicas e quais exigem autenticação.
2.  **Configuração de Segurança "Aberta":** A classe `SecurityConfig.java` é configurada com `permitAll()`, pois a validação do token não ocorre nos filtros padrão do Spring Security, mas em um filtro reativo personalizado do Gateway.
3.   **Filtro de Gateway Customizado:** Um filtro personalizado é aplicado às rotas protegidas, interceptando as requisições e extraindo o **Bearer Token** do cabeçalho `Authorization`.
4. **Delegação da Validação de Token:** Em vez de validar o JWT localmente, o filtro envia uma requisição via `WebClient` para o endpoint `/auth/validate` do serviço de autenticação, responsável pela validação.
5. **Tratamento da Resposta:**
    - Se a validação **falhar**, o Gateway bloqueia a requisição e retorna `401 Unauthorized`.
    - Se a validação **for bem-sucedida**, o `autenticacao-service` retorna os dados do usuário, e o Gateway enriquece a requisição adicionando cabeçalhos HTTP (`X-User-Id`, `X-User-Roles`) antes de encaminhá-la ao serviço de destino.
6. **Encaminhamento Seguro:** As requisições autenticadas são então roteadas de forma transparente e segura aos microsserviços internos.

## Consequências

### Positivas
- **Segurança Centralizada:** Toda a lógica de autenticação ocorre na borda da arquitetura. Os serviços internos confiam apenas nas informações providas pelo Gateway.
- **Desacoplamento:** Cada microsserviço foca apenas na sua lógica de negócio, consumindo cabeçalhos já validados (`X-User-Id`, `X-User-Roles`).
- **Facilidade de Manutenção:** Alterações na política de autenticação podem ser feitas em um único ponto sem impactar os demais serviços.
- **Modelo Zero Trust:** Mesmo dentro da rede interna, a confiança é sempre verificada pelo Gateway.

### Negativas e Riscos
- **Latência Adicional:** Cada requisição autenticada gera uma chamada extra (Gateway -> Auth-Service). O uso de `WebClient` reativo reduz, mas não elimina esse impacto.
- **Single Point of Failure:** O Gateway se torna um componente crítico. Caso falhe, todo o tráfego do sistema é afetado.
- **Dependência do Auth-Service:** Se o serviço de autenticação estiver lento ou indisponível, todo o fluxo de validação é prejudicado.
- **Risco de Cabeçalhos Forjados:** É essencial garantir que os microsserviços internos aceitem apenas chamadas vindas do Gateway, bloqueando acesso direto externo.