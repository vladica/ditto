## CreateThing

Creates a new Thing with ID ``com.acme:xdk_53`` that uses an existing Policy with ID ``com.acme:the_policy_id``.

```json
{
  "topic": "com.acme/xdk_53/things/twin/commands/create",
  "headers": {},
  "path": "/",
  "value": {
    "thingId": "com.acme:xdk_53",
    "policyId": "com.acme:the_policy_id",
    "definition": "com.acme:XDKmodel:1.0.0",
    "attributes": {
      "location": {
        "latitude": 44.673856,
        "longitude": 8.261719
      }
    },
    "features": {
      "accelerometer": {
        "properties": {
          "x": 3.141,
          "y": 2.718,
          "z": 1,
          "unit": "g"
        }
      }
    }
  }
}
```