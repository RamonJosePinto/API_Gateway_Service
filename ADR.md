# Título: Validação Centralizada de Token no API Gateway com Delegação

**Status:** Aceito (Implementado)

## Contexto

Em nossa arquitetura de microsserviços, múltiplos serviços precisam ser protegidos. Implementar a lógica de validação de token JWT em cada um desses serviços criaria duplicação de código e dificultaria a manutenção da segurança.

Precisamos de um ponto de entrada único que atue como o "porteiro" do nosso sistema, garantindo que nenhuma requisição não autenticada chegue aos nossos serviços de negócio.

## Decisão

Foi decidido implementar um **API Gateway** usando **Spring Cloud Gateway** para atuar como a camada de segurança de borda.

A autenticação é tratada da seguinte forma:
1.  **Rotas Públicas vs. Protegidas:** O arquivo `application.yml` define quais rotas são públicas (ex: `/auth/**`, que leva ao serviço de autenticação) e quais são protegidas (todas as demais).
2.  **Configuração de Segurança "Aberta":** A configuração do Spring Security no Gateway (`SecurityConfig.java`) é intencionalmente configurada para `permitAll()`. Isso ocorre porque a segurança não é tratada por filtros de segurança do Spring, mas sim por um filtro customizado do Gateway.
3.  **Filtro de Gateway Customizado (`TokenValidation`):** Um filtro global customizado é aplicado a todas as rotas protegidas.
4.  **Delegação de Validação:** Este filtro intercepta a requisição. Ele extrai o `Bearer Token` do cabeçalho `Authorization`.
5.  **Chamada Reativa (WebClient):** O filtro **não valida o token localmente**. Em vez disso, ele faz uma chamada HTTP POST reativa (não-bloqueante) usando `WebClient` para o endpoint `/auth/validate` do serviço de autenticação, enviando o token para validação.
6.  **Bloqueio ou Enriquecimento:**
    * **Se a validação falhar**, o Gateway bloqueia a requisição e retorna imediatamente um erro `401 Unauthorized` ao cliente final.
    * **Se a validação for bem-sucedida,** o `autenticacao-service` retorna os dados do usuário. O Gateway "enriquece" a requisição original, injetando esses dados em cabeçalhos HTTP confiáveis.
7.  **Requisição Segura:** A requisição modificada é finalmente encaminhada para o microsserviço de destino.

## Consequências

O que se torna mais fácil ou mais difícil como resultado dessa mudança?

* **Positivas:**
    * **Segurança Centralizada (Zero Trust):** A segurança é tratada na borda. Os serviços internos não precisam mais se preocupar com tokens, chaves secretas ou validação. Eles operam em um modelo de "confiança zero", onde a autenticação é garantida pelo Gateway.
    * **Desacoplamento e Simplificação:** Os serviços internos são drasticamente simplificados. Eles não precisam mais do `UserClient` em seus controladores para verificar permissões. A lógica de negócio pode simplesmente ler os cabeçalhos `X-User-Roles` e `X-User-Id`.
    * **Flexibilidade:** A lógica de autenticação pode ser alterada em um único lugar sem impactar nenhum outro microsserviço.

* **Negativas e Riscos:**
    * **Latência de Rede:** Cada requisição protegida agora incorre em uma chamada de rede extra (Gateway -> Auth-Service) antes de atingir o serviço de destino. Isso adiciona latência. (Isso é mitigado pelo uso de `WebClient` reativo, que é mais eficiente que `RestTemplate` bloqueante).
    * **Single Point of Failure:** O API Gateway torna-se o componente mais crítico da arquitetura. Se ele falhar, toda a aplicação fica indisponível.
    * **Dependência Crítica do Auth-Service:** A disponibilidade e o tempo de resposta do `autenticacao-service` agora são hipercríticos. Se ele ficar lento, todo o fluxo de requisições será represado no Gateway.
    * **Segurança de Rede Interna:** Este padrão pressupõe que a rede interna é segura. Os microsserviços devem ser configurados para somente aceitar tráfego vindo do API Gateway, impedindo que um invasor chame um serviço interno diretamente, forjando os cabeçalhos `X-User-Id`.