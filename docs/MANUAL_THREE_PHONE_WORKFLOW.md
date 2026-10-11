# Sethu — manual connections, automatic forwarding

Policy: **connections are made only by the user; forwarding is automatic once the needed connection exists.**

* Nothing listens or dials on app start. A listener exists only after **Start Server** (Direct Device Link) and is
  closed by **Stop Server**. Stop Server does *not* drop an established link — use **Disconnect** (or turn Bluetooth off).
* An outbound connection exists only after tapping a paired device in Direct Device Link.
* The delivery engine (`StoreAndForwardController`) never connects, disconnects, or starts a listener. It reacts to a
  Connected + role-announced link: Sender→Relay/Coordinator and Relay→Coordinator pending reports are sent at once.
* Max 3 transmissions of a report per physical connection (no retry storm). A new connection resets the budget.
* Allowed role pairs: Sender–Relay, Relay–Coordinator, Sender–Coordinator. Anything else is dropped after the role
  handshake with the message "not a valid peer for this role".
* ACK rules: Relay ACKs the Sender only after its durable write; Coordinator ACKs the Relay only after its durable
  write; Relay marks DELIVERED only for a COORDINATOR_ACK with the same report ID, on the same session, from a peer
  that announced the Coordinator role.

Limitations: RFCOMM pairing is not application-level authentication; frames are not encrypted or signed by Sethu.
No foreground service: if Android kills the app, nothing runs until the user reopens it (queues stay on disk).
Release APK is unsigned (no signing config in the project).
