const WebSocket = require('ws');


const wss = new WebSocket.Server({ port: 8081 });

let supportAgent = null; 
const customers = new Set(); 


wss.on('connection', ws => {
  console.log('Новое подключение установлено');

  if (!supportAgent) {
    supportAgent = ws;
    ws.send(JSON.stringify({
      type: 'role',
      role: 'support',
      message: 'Вы — агент поддержки.'
    }));
    console.log('Отправлено сообщение агенту: Вы — агент поддержки.');
  } else {
    customers.add(ws);
    ws.send(JSON.stringify({
      type: 'role',
      role: 'customer',
      message: 'Подключено к поддержке. Ожидайте ответа.'
    }));
    console.log('Отправлено сообщение клиенту: Подключено к поддержке. Ожидайте ответа.');

    if (supportAgent) {
      supportAgent.send(JSON.stringify({
        type: 'notification',
        message: 'Новый клиент подключился.'
      }));
      console.log('Отправлено агенту: Новый клиент подключился.');
    }
  }

  // Обработчик получения сообщений
  ws.on('message', message => {
    const msgString = message.toString();
    console.log('Получено сообщение:', msgString);

    if (ws === supportAgent) {
      
      customers.forEach(customer => {
        customer.send(JSON.stringify({
          type: 'message',
          sender: 'support',
          message: msgString
        }));
        console.log(`Отправлено клиенту: Поддержка: ${msgString}`);
      });

      
      ws.send(JSON.stringify({
        type: 'message',
        sender: 'self',
        message: msgString
      }));
      console.log(`Отправлено агенту (себе): Вы: ${msgString}`);
    } else {
      
      if (supportAgent) {
        supportAgent.send(JSON.stringify({
          type: 'message',
          sender: 'customer',
          message: msgString
        }));
        console.log(`Отправлено агенту: Клиент: ${msgString}`);
      }

      
      ws.send(JSON.stringify({
        type: 'message',
        sender: 'self',
        message: msgString
      }));
      console.log(`Отправлено клиенту (себе): Вы: ${msgString}`);
    }
  });

  
  ws.on('close', () => {
    console.log('Соединение закрыто');
    if (ws === supportAgent) {
      supportAgent = null;
      customers.forEach(customer => {
        customer.send(JSON.stringify({
          type: 'notification',
          message: 'Агент поддержки отключился.'
        }));
      });
      console.log('Агент поддержки отключился');
    } else {
      customers.delete(ws);
      if (supportAgent) {
        supportAgent.send(JSON.stringify({
          type: 'notification',
          message: 'Клиент отключился.'
        }));
      }
      console.log('Клиент отключился');
    }
  });
});

console.log('WebSocket-сервер запущен на порту 8081');