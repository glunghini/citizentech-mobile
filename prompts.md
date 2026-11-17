# Registro de uso de IA

A GS permite usar IA como copiloto, desde que os prompts fiquem registrados e a equipe saiba explicar cada linha do código.
Este arquivo registra o que foi pedido à IA (Claude) na criação da base deste app. **Acrescentem aqui os prompts que vocês usarem daqui em diante.**

## Sessão de 17/11/2026: criação da base do app

| # | Prompt (resumo fiel) | O que a IA entregou |
| --- | --- | --- |
| 1 | “Leia esse arquivo e me fale o que está escrito” (anexo: especificação do app CitizenTech MVP) | Resumo da especificação: objetivo, divisão de responsabilidades, contrato HTTP v1, modo mock e critérios de aceite. |
| 2 | “Legal, iremos aplicar essa ideia do meu grupo nesse PDF no trabalho de Mobile Development e IOT. Leia ele inteiro na ideia do que tem que fazer, porém iremos apenas fazer o trabalho de Mobile” (anexo: PDF da GS CitizenTech) | Leitura do briefing da GS, dos requisitos de Mobile (6 critérios) e comparação com a especificação. |
| 3 | “Cada projeto é meio que independente, então faremos nosso próprio banco de dados e nossas próprias coisas… o que temos que focar é seguir a mesma ideia que está descrita no md” | Perguntas de escopo. Respostas da equipe: banco **Firebase Firestore**; orientações por **mock + API, com chave de configuração**; **começar do zero**; **projeto 100% gratuito**. |
| 4 | (continuação) Geração do projeto | Projeto Expo (SDK 58) com Expo Router, módulo nativo Kotlin (serviço de acessibilidade, leitura da tela, overlay, voz, cliente HTTP v1 e mock), Firestore com login anônimo e regras, página de demonstração, testes do contrato e este README. |

## Pontos que a equipe deve saber explicar

- Por que o ciclo de orientação roda no Kotlin (`AssistAccessibilityService`) e não em um timer do JavaScript.
- Como o retângulo do destaque é obtido (`getBoundsInScreen`) e convertido para a janela do overlay (`getLocationOnScreen`).
- Por que o app nunca envia coordenadas nem valores digitados (`ScreenReader.inputLabel`).
- Como a resposta é validada (`GuidanceContract.parseResponse`) e quando ela é descartada (página mudou entre leitura e resposta).
- Como o histórico chega ao Firestore (`SessionBus` → `consumeFinishedSessions` → `saveSession`) e o que as regras do Firestore permitem.
