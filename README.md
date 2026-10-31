# Forge-Client-Reset-Packet

Client-side Forge mod that makes server switches behind a proxy (Velocity + Ambassador/PCF) work like a clean reconnect.

## Minecraft 1.21.1 (Forge 52.x) — branch `1.21.1`

Since Minecraft 1.20.2 the proxy no longer needs a custom reset packet: Velocity switches servers by sending the
vanilla "start configuration" packet, and Forge runs its whole handshake (mod list, channels, registry sync, config
sync) in the configuration phase again for the new backend. Backend servers still need
[Proxy-Compatible-Forge](https://github.com/adde0109/Proxy-Compatible-Forge) (modern forwarding + modded command
arguments). Ambassador is only for 1.13–1.20.1.

What vanilla re-configuration does not do is the client cleanup Forge performs on a real disconnect. On 1.21.1 this
mod adds it when the proxy switches servers:

- fires `ClientPlayerNetworkEvent.LoggingOut` and `LevelEvent.Unload` for the old server/level (Forge only fires
  them from `Minecraft#disconnect`, but fires `LoggingIn` again for the new server);
- reverts registries to the frozen state before the new server's registry snapshot arrives;
- resets Forge's per-connection network state (connection type, channel lists, mod list) so the client
  re-announces its channels to the new backend and a modded → vanilla switch is detected;
- pauses Oculus/Iris shaders across the switch and turns them back on afterwards (workaround for #3), if a shader
  mod that ships the Iris API is installed.

The mod is client-only and optional on servers. Translations live in
`forge/src/main/resources/assets/clientresetpacket/lang/` (`en_us.json`, `zh_cn.json`); contributions welcome.

Build: `./gradlew build` (Java 21). The jar ends up in `forge/build/libs/` and `release/`.
