# Contract: appleskin 3.0.10+mc26.3

Source: `appleskin-fabric-mc26_3-3_0_10.jar`. Environment `*`. Depends: `{'fabricloader': '>=0.15.10', 'fabric-api': '*'}`.

## Entrypoints

- client: `squeek.appleskin.AppleSkin`
- main: `squeek.appleskin.AppleSkinCommon`
- modmenu: `squeek.appleskin.gui.ModMenuIntegration`
- rei_client: `squeek.appleskin.client.REITooltipPlugin`

## Totals (lower bounds)

- Mojang classes referenced: 55
- Mojang methods referenced: 68
- Mojang fields referenced: 19
- Fabric API classes referenced: 9
- Mixin classes: 8

## Fabric API classes used

- `net/fabricmc/fabric/api/client/networking/v1/ClientPlayNetworking`
- `net/fabricmc/fabric/api/client/networking/v1/ClientPlayNetworking$Context`
- `net/fabricmc/fabric/api/client/networking/v1/ClientPlayNetworking$PlayPayloadHandler`
- `net/fabricmc/fabric/api/event/Event`
- `net/fabricmc/fabric/api/event/EventFactory`
- `net/fabricmc/fabric/api/event/lifecycle/v1/ServerTickEvents`
- `net/fabricmc/fabric/api/event/lifecycle/v1/ServerTickEvents$EndLevelTick`
- `net/fabricmc/fabric/api/networking/v1/PayloadTypeRegistry`
- `net/fabricmc/fabric/api/networking/v1/ServerPlayNetworking`

## Mixins

### `squeek/appleskin/mixin/ClientTooltipComponentMixin`

Targets: `class Lnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipComponent;`

- `AppleSkin_of(Lnet/minecraft/util/FormattedCharSequence;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V`: @Inject(at=[@At(value=HEAD)], method=[create(Lnet/minecraft/util/FormattedCharSequence;)Lnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipComponent;], cancellable=1)
- `AppleSkin_ofData(Lnet/minecraft/world/inventory/tooltip/TooltipComponent;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V`: @Inject(at=[@At(value=HEAD)], method=[create(Lnet/minecraft/world/inventory/tooltip/TooltipComponent;)Lnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipComponent;], cancellable=1)

### `squeek/appleskin/mixin/FoodDataMixin`

Targets: `class Lnet/minecraft/world/food/FoodData;`

- field `exhaustionLevel` `F`: @Shadow()

### `squeek/appleskin/mixin/HudMixin`

Targets: `class Lnet/minecraft/client/gui/Hud;`

- `renderFoodPre(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/entity/player/Player;IILorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V`: @Inject(at=[@At(value=HEAD)], method=[extractFood])
- `renderFoodPost(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/entity/player/Player;IILorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V`: @Inject(at=[@At(value=RETURN)], method=[extractFood])
- `renderHealthPost(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/entity/player/Player;IIIIFIIIZLorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V`: @Inject(at=[@At(value=RETURN)], method=[extractHearts])

### `squeek/appleskin/mixin/ItemStackMixin`

Targets: `class Lnet/minecraft/world/item/ItemStack;`

- `getTooltipFromItem(Lnet/minecraft/world/item/Item$TooltipContext;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/TooltipFlag;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V`: @Inject(at=[@At(value=RETURN)], method=[getTooltipLines])

### `squeek/appleskin/mixin/JEIRenderHelperMixin`

Targets: `class Lmezz/jei/fabric/platform/RenderHelper;`

- `renderFoodPre(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Ljava/util/List;IILnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V`: @Inject(at=[@At(value=HEAD)], method=[renderTooltip], require=0)

### `squeek/appleskin/mixin/MinecraftMixin`

Targets: `class Lnet/minecraft/client/Minecraft;`

- `onTick(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V`: @Inject(at=[@At(value=HEAD)], method=[tick])

### `squeek/appleskin/mixin/PlayerListMixin`

Targets: `class Lnet/minecraft/server/players/PlayerList;`

- `onPlayerConnect(Lnet/minecraft/network/Connection;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/network/CommonListenerCookie;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V`: @Inject(at=[@At(value=TAIL)], method=[placeNewPlayer])

### `squeek/appleskin/mixin/ServerPlayerMixin`

Targets: `class Lnet/minecraft/server/level/ServerPlayer;`

- `onUpdate(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V`: @Inject(at=[@At(value=HEAD)], method=[tick])

## Injector and annotation counts

- Inject: 10
- Shadow: 1

## Mojang methods referenced

- `net/minecraft/client/Minecraft.execute(Ljava/lang/Runnable;)V`
- `net/minecraft/client/Minecraft.getInstance()Lnet/minecraft/client/Minecraft;`
- `net/minecraft/client/Minecraft.getWindow()Lcom/mojang/blaze3d/platform/Window;`
- `net/minecraft/client/gui/Font.width(Ljava/lang/String;)I`
- `net/minecraft/client/gui/GuiGraphicsExtractor.blit(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIIII)V`
- `net/minecraft/client/gui/GuiGraphicsExtractor.blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V`
- `net/minecraft/client/gui/GuiGraphicsExtractor.blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIII)V`
- `net/minecraft/client/gui/GuiGraphicsExtractor.pose()Lorg/joml/Matrix3x2fStack;`
- `net/minecraft/client/gui/GuiGraphicsExtractor.text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V`
- `net/minecraft/client/gui/Hud.getGuiTicks()I`
- `net/minecraft/client/gui/components/debug/DebugScreenDisplayer.addToGroup(Lnet/minecraft/resources/Identifier;Ljava/lang/String;)V`
- `net/minecraft/client/gui/components/debug/DebugScreenEntries.register(Lnet/minecraft/resources/Identifier;Lnet/minecraft/client/gui/components/debug/DebugScreenEntry;)Lnet/minecraft/resources/Identifier;`
- `net/minecraft/client/player/LocalPlayer.getFoodData()Lnet/minecraft/world/food/FoodData;`
- `net/minecraft/client/player/LocalPlayer.getHealth()F`
- `net/minecraft/client/player/LocalPlayer.level()Lnet/minecraft/world/level/Level;`
- `net/minecraft/core/Holder.value()Ljava/lang/Object;`
- `net/minecraft/network/FriendlyByteBuf.readBoolean()Z`
- `net/minecraft/network/FriendlyByteBuf.readFloat()F`
- `net/minecraft/network/FriendlyByteBuf.writeBoolean(Z)Lnet/minecraft/network/FriendlyByteBuf;`
- `net/minecraft/network/FriendlyByteBuf.writeFloat(F)Lnet/minecraft/network/FriendlyByteBuf;`
- `net/minecraft/network/chat/Component.nullToEmpty(Ljava/lang/String;)Lnet/minecraft/network/chat/Component;`
- `net/minecraft/network/chat/Component.translatable(Ljava/lang/String;)Lnet/minecraft/network/chat/MutableComponent;`
- `net/minecraft/network/chat/Component.translatable(Ljava/lang/String;[Ljava/lang/Object;)Lnet/minecraft/network/chat/MutableComponent;`
- `net/minecraft/network/protocol/common/custom/CustomPacketPayload.codec(Lnet/minecraft/network/codec/StreamMemberEncoder;Lnet/minecraft/network/codec/StreamDecoder;)Lnet/minecraft/network/codec/StreamCodec;`
- `net/minecraft/network/protocol/common/custom/CustomPacketPayload$Type.<init>(Lnet/minecraft/resources/Identifier;)V`
- `net/minecraft/resources/Identifier.fromNamespaceAndPath(Ljava/lang/String;Ljava/lang/String;)Lnet/minecraft/resources/Identifier;`
- `net/minecraft/resources/Identifier.withDefaultNamespace(Ljava/lang/String;)Lnet/minecraft/resources/Identifier;`
- `net/minecraft/server/level/ServerLevel.getGameRules()Lnet/minecraft/world/level/gamerules/GameRules;`
- `net/minecraft/server/level/ServerLevel.players()Ljava/util/List;`
- `net/minecraft/server/level/ServerPlayer.getFoodData()Lnet/minecraft/world/food/FoodData;`
- `net/minecraft/server/level/ServerPlayer.getUUID()Ljava/util/UUID;`
- `net/minecraft/util/Mth.floor(D)I`
- `net/minecraft/util/StringDecomposer.iterateFormatted(Lnet/minecraft/network/chat/FormattedText;Lnet/minecraft/network/chat/Style;Lnet/minecraft/util/FormattedCharSink;)Z`
- `net/minecraft/util/Util.getPlatform()Lnet/minecraft/util/Util$OS;`
- `net/minecraft/world/effect/MobEffect.getCategory()Lnet/minecraft/world/effect/MobEffectCategory;`
- `net/minecraft/world/effect/MobEffectInstance.getAmplifier()I`
- `net/minecraft/world/effect/MobEffectInstance.getDuration()I`
- `net/minecraft/world/effect/MobEffectInstance.getEffect()Lnet/minecraft/core/Holder;`
- `net/minecraft/world/entity/Entity.<init>(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/level/Level;)V`
- `net/minecraft/world/entity/player/Player.canEat(Z)Z`
- `net/minecraft/world/entity/player/Player.getAbsorptionAmount()F`
- `net/minecraft/world/entity/player/Player.getFoodData()Lnet/minecraft/world/food/FoodData;`
- `net/minecraft/world/entity/player/Player.getHealth()F`
- `net/minecraft/world/entity/player/Player.getMainHandItem()Lnet/minecraft/world/item/ItemStack;`
- `net/minecraft/world/entity/player/Player.getMaxHealth()F`
- `net/minecraft/world/entity/player/Player.getOffhandItem()Lnet/minecraft/world/item/ItemStack;`
- `net/minecraft/world/entity/player/Player.hasEffect(Lnet/minecraft/core/Holder;)Z`
- `net/minecraft/world/entity/player/Player.isHurt()Z`
- `net/minecraft/world/entity/player/Player.level()Lnet/minecraft/world/level/Level;`
- `net/minecraft/world/food/FoodData.getFoodLevel()I`
- `net/minecraft/world/food/FoodData.getSaturationLevel()F`
- `net/minecraft/world/food/FoodData.setSaturation(F)V`
- `net/minecraft/world/food/FoodProperties.canAlwaysEat()Z`
- `net/minecraft/world/food/FoodProperties.nutrition()I`
- `net/minecraft/world/food/FoodProperties.saturation()F`
- `net/minecraft/world/food/FoodProperties$Builder.<init>()V`
- `net/minecraft/world/food/FoodProperties$Builder.build()Lnet/minecraft/world/food/FoodProperties;`
- `net/minecraft/world/item/ItemStack.getOrDefault(Lnet/minecraft/core/component/DataComponentType;Ljava/lang/Object;)Ljava/lang/Object;`
- `net/minecraft/world/item/ItemStack.has(Lnet/minecraft/core/component/DataComponentType;)Z`
- `net/minecraft/world/item/ItemStack.isEmpty()Z`
- `net/minecraft/world/item/TooltipFlag.isCreative()Z`
- `net/minecraft/world/item/component/Consumable.onConsumeEffects()Ljava/util/List;`
- `net/minecraft/world/item/component/TooltipDisplay.hideTooltip()Z`
- `net/minecraft/world/item/consume_effects/ApplyStatusEffectsConsumeEffect.effects()Ljava/util/List;`
- `net/minecraft/world/level/Level.getDifficulty()Lnet/minecraft/world/Difficulty;`
- `net/minecraft/world/level/Level.getLevelData()Lnet/minecraft/world/level/storage/LevelData;`
- `net/minecraft/world/level/gamerules/GameRules.get(Lnet/minecraft/world/level/gamerules/GameRule;)Ljava/lang/Object;`
- `net/minecraft/world/level/storage/LevelData.isHardcore()Z`

## Mojang fields referenced

- `net/minecraft/client/Minecraft.gui:Lnet/minecraft/client/gui/Gui;`
- `net/minecraft/client/Minecraft.player:Lnet/minecraft/client/player/LocalPlayer;`
- `net/minecraft/client/gui/Gui.hud:Lnet/minecraft/client/gui/Hud;`
- `net/minecraft/client/renderer/RenderPipelines.GUI_TEXTURED:Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;`
- `net/minecraft/core/component/DataComponents.CONSUMABLE:Lnet/minecraft/core/component/DataComponentType;`
- `net/minecraft/core/component/DataComponents.FOOD:Lnet/minecraft/core/component/DataComponentType;`
- `net/minecraft/core/component/DataComponents.TOOLTIP_DISPLAY:Lnet/minecraft/core/component/DataComponentType;`
- `net/minecraft/network/chat/Style.EMPTY:Lnet/minecraft/network/chat/Style;`
- `net/minecraft/network/chat/contents/PlainTextContents.EMPTY:Lnet/minecraft/network/chat/contents/PlainTextContents;`
- `net/minecraft/util/Util$OS.OSX:Lnet/minecraft/util/Util$OS;`
- `net/minecraft/world/Difficulty.PEACEFUL:Lnet/minecraft/world/Difficulty;`
- `net/minecraft/world/InteractionResult.SUCCESS:Lnet/minecraft/world/InteractionResult$Success;`
- `net/minecraft/world/effect/MobEffectCategory.HARMFUL:Lnet/minecraft/world/effect/MobEffectCategory;`
- `net/minecraft/world/effect/MobEffects.POISON:Lnet/minecraft/core/Holder;`
- `net/minecraft/world/effect/MobEffects.REGENERATION:Lnet/minecraft/core/Holder;`
- `net/minecraft/world/effect/MobEffects.WITHER:Lnet/minecraft/core/Holder;`
- `net/minecraft/world/item/component/Consumables.DEFAULT_FOOD:Lnet/minecraft/world/item/component/Consumable;`
- `net/minecraft/world/item/component/TooltipDisplay.DEFAULT:Lnet/minecraft/world/item/component/TooltipDisplay;`
- `net/minecraft/world/level/gamerules/GameRules.NATURAL_HEALTH_REGENERATION:Lnet/minecraft/world/level/gamerules/GameRule;`
