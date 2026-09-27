# MAAN caller name/number fix

This build fixes the incoming-call data path.

## What changed
- Requests the Android call-screening role for MAAN on Android 10+.
- Requests READ_CALL_LOG in addition to phone/contact permissions.
- CallScreeningService starts the MAAN connection service before sending `incoming_call`.
- Incoming call messages are queued if the connection service has not been created yet.
- Existing contact lookup is retained, so a saved contact name is sent to the Mac; otherwise the phone number/Unknown Caller is used.

## After installing
1. Open MAAN once.
2. Allow Contacts, Phone, Call Log, and Notification permissions.
3. When Android asks for the call-screening role, select MAAN and allow it.
4. Keep MAAN connected to the Mac.
5. Test an incoming call.

The Mac already reads `name` and `number` from the `incoming_call` message.
