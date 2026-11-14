# Mojang API demand across jars

Lower bounds (constant pool only). Client = net/minecraft/client, blaze3d, renderpearl.

| Jar | classes | methods | fields | client classes | client methods |
|---|---|---|---|---|---|
| appleskin-fabric-mc26_3-3_0_10.jar | 59 | 71 | 19 | 15 | 16 |
| Clumps-fabric-26_3-26_3_2.jar | 14 | 23 | 2 | 0 | 0 |
| xaerominimap-fabric-26_3-26_6_2.jar | 464 | 860 | 205 | 263 | 525 |
| placeholder-api-3_2_0_26_3.jar | 131 | 299 | 61 | 6 | 9 |
| modmenu-21_0_1.jar | 303 | 535 | 104 | 97 | 140 |
| fabric-api-0_166_2_26_3.jar | 907 | 1710 | 463 | 256 | 382 |

**Union:** 1277 classes (464 client, 813 common), 2672 methods (896 client), 684 fields.

Used by 2+ jars: 381 classes, 608 methods, 122 fields. Used by 3+ jars: 135 classes, 161 methods.

## Classes by package (top 14)

- 169  `net/minecraft/world/level`
- 136  `net/minecraft/client/renderer`
- 106  `net/minecraft/client/gui`
- 63  `net/minecraft/world/item`
- 62  `net/minecraft/client/model`
- 61  `net/minecraft/world/entity`
- 50  `net/minecraft/network/chat`
- 43  `net/minecraft/network/protocol`
- 41  `net/minecraft/client/resources`
- 32  `com/mojang/renderpearl/api`
- 29  `net/minecraft/server/packs`
- 19  `net/minecraft/core/component`
- 11  `net/minecraft/server/level`
- 11  `net/minecraft/client/multiplayer`

## Most demanded classes (by number of jars)

- 6  `net/minecraft/server/level/ServerLevel`  (common)
- 6  `net/minecraft/server/level/ServerPlayer`  (common)
- 6  `net/minecraft/world/entity/Entity`  (common)
- 6  `net/minecraft/world/item/ItemStack`  (common)
- 5  `com/mojang/datafixers/util/Either`  (common)
- 5  `net/minecraft/client/Minecraft`  (client)
- 5  `net/minecraft/client/player/LocalPlayer`  (client)
- 5  `net/minecraft/core/Holder`  (common)
- 5  `net/minecraft/core/component/DataComponents`  (common)
- 5  `net/minecraft/nbt/CompoundTag`  (common)
- 5  `net/minecraft/network/chat/Component`  (common)
- 5  `net/minecraft/network/chat/Style`  (common)
- 5  `net/minecraft/resources/Identifier`  (common)
- 5  `net/minecraft/util/Mth`  (common)
- 5  `net/minecraft/util/Util`  (common)
- 5  `net/minecraft/world/entity/player/Player`  (common)
- 5  `net/minecraft/world/level/Level`  (common)
- 4  `com/mojang/authlib/GameProfile`  (common)
- 4  `com/mojang/blaze3d/platform/InputConstants`  (client)
- 4  `com/mojang/datafixers/util/Pair`  (common)
- 4  `com/mojang/renderpearl/api/pipeline/RenderPipeline`  (client)
- 4  `com/mojang/serialization/Codec`  (common)
- 4  `com/mojang/serialization/DataResult`  (common)
- 4  `com/mojang/serialization/DynamicOps`  (common)
- 4  `com/mojang/serialization/JsonOps`  (common)

## Coverage by the real 26.3 server jar (measured with a script over the user's server.jar, class names only)

The bundled server jar plus its bundled libraries hold 30,868 classes. Of the 813 referenced "common" classes, 809 exist
there; the 4 that do not are Realms client classes (`com/mojang/realmsclient/...`), so they belong to the client tier.
None of the 464 client-tier classes exist in the server jar. Class presence only: member signatures were not compared.
So running the real server classes on the JVM would supply the common tier; the client tier (about 468 classes in this
sample, 896+ methods) is what a Horizon facade has to provide. The real jar is downloaded on the device at run time
(as the probe already does); it is not shipped or committed.
