const mineflayer = require('mineflayer')
const { once } = require('node:events')
const delay = ms => new Promise(resolve => setTimeout(resolve, ms))
const port = Number(process.argv[2])
const version = process.argv[3] || '1.21.8'
const bots = []

function connect (username) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port, username, auth: 'offline', version })
  bots.push(bot)
  const messages = []
  let failure
  bot.on('messagestr', message => {
    if (message.startsWith('IBCLIENT_')) { messages.push(message); console.log(username, message) }
  })
  bot.on('error', error => { failure = error })
  bot.on('kicked', reason => { failure = new Error(JSON.stringify(reason)) })
  bot.on('death', () => setTimeout(() => bot.respawn(), 250))
  bot.waitMessage = async prefix => {
    const deadline = Date.now() + 45000
    while (Date.now() < deadline) {
      if (failure) throw failure
      const index = messages.findIndex(message => message.startsWith(prefix))
      if (index >= 0) return messages.splice(index, 1)[0]
      await delay(50)
    }
    throw new Error(`Timed out waiting for ${username}: ${prefix}`)
  }
  return bot
}

async function main () {
  let victim = connect('IBVictim')
  const attacker = connect('IBAttacker')
  await Promise.all([victim.waitMessage('IBCLIENT_READY'), attacker.waitMessage('IBCLIENT_READY')])
  const Item = require('prismarine-item')(victim.registry)
  attacker.on('messagestr', message => {
    if (message === 'IBCLIENT_ATTACK') {
      const target = attacker.players.IBVictim?.entity
      if (!target) throw new Error('Victim entity is missing')
      attacker.setQuickBarSlot(0)
      attacker.attack(target)
    }
  })
  for (const keep of [false, true]) {
    for (const cause of ['kill', 'fall', 'void', 'combat']) {
      victim.chat(`/ibclient death ${cause} ${keep}`)
      await victim.waitMessage(`IBCLIENT_DEATH_PASS ${cause} ${keep}`)
      await delay(1500) // Respawn packets and the following teleport must be acknowledged.
    }
  }
  for (const mode of ['survival', 'creative']) {
    const opened = once(victim, 'windowOpen')
    victim.chat(`/ibclient gui ${mode}`)
    const [window] = await opened
    await delay(250)
    // These are real client inventory packets, including modes not simulated by mineflayer.
    const click = (slot, mouseButton, clickMode) => victim._client.write('window_click', {
      windowId: window.id, stateId: -1, slot, mouseButton, mode: clickMode,
      changedSlots: [], cursorItem: null
    })
    for (const [slot, button, clickMode] of [[0, 0, 0], [0, 0, 1], [0, 1, 2], [0, 40, 2], [0, 0, 6],
      [-999, 0, 5], [0, 1, 5], [-999, 2, 5]]) {
      click(slot, button, clickMode)
      await delay(150)
    }
    if (mode === 'creative') {
      victim._client.write('set_creative_slot', { slot: 36, item: Item.toNotch(window.slots[0]) })
      await delay(250)
    }
    victim.chat('/ibclient gui-check')
    await victim.waitMessage('IBCLIENT_GUI_PASS')
  }
  victim.quit()
  await delay(1000)
  attacker.chat('/ibclient queue')
  await attacker.waitMessage('IBCLIENT_QUEUE_READY')
  victim = connect('IBVictim')
  await victim.waitMessage('IBCLIENT_JOIN_PASS')
  attacker.chat('/ibclient finish')
  console.log('INVENTORYBACKUP_CLIENT_SCRIPT_PASS')
  await delay(1000)
}

main().catch(error => { console.error(error); process.exitCode = 1 }).finally(() => {
  for (const bot of bots) bot.quit()
})
