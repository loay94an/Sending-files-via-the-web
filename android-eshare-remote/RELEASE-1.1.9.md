# Wasla EShare Remote 1.1.9 (Code 20)

## Discovery and connection
- Discover EShare receivers by UDP broadcast on active IPv4 interfaces.
- Fall back to a subnet scan instead of trusting the previously saved IP address.
- Collect multiple receivers and show a selection dialog when more than one is found.
- Persist the selected receiver and its discovered control port for reconnect attempts.

## Arabic and Unicode text
- Use the EShare receiver's original CONTENT input protocol.
- Send UTF-8 composing text (action 0) followed by commit text (action 1).
- Avoid translating Arabic characters into Latin keyboard-position key codes.

## Retained from 1.1.8
- Touchpad long-press pointer hold/release.
- Socket disconnect monitoring and reconnect retry.

Build status and target-TV behavior must be verified separately. Network discovery requires the phone and receiver to share a reachable local network; guest isolation or VLAN separation can block discovery.
