import { Body, Card, Screen, Title } from '../src/ui';

export default function AboutScreen() {
  return (
    <Screen>
      <Title>Para quem é</Title>
      <Body>
        Para pessoas idosas e para quem tem dificuldade com tecnologia e precisa usar um site de serviço no celular.
      </Body>

      <Card>
        <Title>Como funciona</Title>
        <Body>1. Você diz ou escreve o que quer fazer.</Body>
        <Body>2. Você abre a página no Chrome. Um painel aparece por cima, com “Próximo passo” e “Encerrar”.</Body>
        <Body>3. Ao tocar em “Próximo passo”, o assistente lê a página, contorna o botão certo e fala o que fazer.</Body>
        <Body>4. Você mesmo toca e preenche. Depois, pede o próximo passo.</Body>
      </Card>

      <Card tone="info">
        <Title>Privacidade</Title>
        <Body>• A leitura só acontece durante a ajuda e só na janela do Chrome.</Body>
        <Body>• O que você digita em campos e as senhas nunca são lidos.</Body>
        <Body>• O histórico guarda o objetivo, a data e as orientações dadas. Não guarda o conteúdo da página.</Body>
        <Body>• Use somente dados fictícios nos testes.</Body>
      </Card>

      <Card>
        <Title>Limites</Title>
        <Body>• Funciona no Android com o Chrome.</Body>
        <Body>• O assistente não toca na tela por você e pode errar: confira sempre antes de confirmar algo.</Body>
        <Body>• No modo de teste, as respostas são simuladas e não vêm de uma inteligência artificial.</Body>
      </Card>
    </Screen>
  );
}
