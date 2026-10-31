package com.mobtrader;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

public class MobTrader implements ModInitializer {

    public static final String MOD_ID = "mobtrader";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Config config = new Config();

    @Override
    public void onInitialize() {
        config = loadConfig();

        UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
            if (level.isClientSide()) return InteractionResult.PASS;
            if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;
            if (!(entity instanceof LivingEntity mob)) return InteractionResult.PASS;
            if (entity instanceof Player) return InteractionResult.PASS;

            if (entity instanceof Merchant) return InteractionResult.PASS;

            if (config.requireSneak && !serverPlayer.isShiftKeyDown()) {
                return InteractionResult.PASS;
            }

            MerchantOffers offers = offersFor(mob);
            if (offers.isEmpty()) return InteractionResult.PASS;

            MobMerchant merchant = new MobMerchant(offers);
            merchant.setTradingPlayer(serverPlayer);

            Component title = mob.getDisplayName();
            OptionalInt containerId = serverPlayer.openMenu(new SimpleMenuProvider(
                    (id, inventory, p) -> new MerchantMenu(id, inventory, merchant),
                    title
            ));

            if (containerId.isPresent()) {
                serverPlayer.sendMerchantOffers(containerId.getAsInt(), offers, 1, 0, false, false);
            }

            return InteractionResult.SUCCESS_SERVER;
        });
    }

    private static class MobMerchant implements Merchant {

        private final MerchantOffers offers;
        private Player tradingPlayer;
        private int xp;

        MobMerchant(MerchantOffers offers) {
            this.offers = offers;
        }

        @Override
        public void setTradingPlayer(Player player) {
            this.tradingPlayer = player;
        }

        @Override
        public Player getTradingPlayer() {
            return tradingPlayer;
        }

        @Override
        public MerchantOffers getOffers() {
            return offers;
        }

        @Override
        public void overrideOffers(MerchantOffers newOffers) {
        }

        @Override
        public void notifyTrade(MerchantOffer offer) {
            offer.increaseUses();
        }

        @Override
        public void notifyTradeUpdated(ItemStack stack) {
        }

        @Override
        public int getVillagerXp() {
            return xp;
        }

        @Override
        public void overrideXp(int amount) {
            this.xp = amount;
        }

        @Override
        public boolean showProgressBar() {
            return false;
        }

        @Override
        public SoundEvent getNotifyTradeSound() {
            return SoundEvents.VILLAGER_YES;
        }

        @Override
        public boolean isClientSide() {
            return false;
        }

        @Override
        public boolean stillValid(Player player) {
            return player == tradingPlayer;
        }
    }

    public static class Config {
        public boolean requireSneak = false;
        public Map<String, List<Trade>> tables = new HashMap<>();
    }

    public static class Trade {
        public String buy;
        public int buyCount = 1;
        public String buyB;
        public int buyBCount = 1;
        public String sell;
        public int sellCount = 1;
        public int maxUses = 16;
    }

    private static Config loadConfig() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("mobtrader.json");
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, DEFAULT_CONFIG, StandardCharsets.UTF_8);
            }
            Config loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Config.class);
            if (loaded != null) {
                if (loaded.tables == null) loaded.tables = new HashMap<>();
                return loaded;
            }
        } catch (Exception e) {
            System.err.println("[" + MOD_ID + "] config could not be read, no trades loaded: " + e);
        }
        return new Config();
    }

    private static MerchantOffers offersFor(LivingEntity mob) {
        MerchantOffers offers = new MerchantOffers();

        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType());
        List<Trade> trades = config.tables.get(id.toString());
        if (trades == null) trades = config.tables.get("default");
        if (trades == null) return offers;

        for (Trade trade : trades) {
            Item buy = item(trade.buy);
            Item sell = item(trade.sell);
            if (buy == null || sell == null) continue;

            Optional<ItemCost> second = Optional.empty();
            if (trade.buyB != null && !trade.buyB.isBlank()) {
                Item buyB = item(trade.buyB);
                if (buyB != null) second = Optional.of(new ItemCost(buyB, trade.buyBCount));
            }

            offers.add(new MerchantOffer(
                    new ItemCost(buy, trade.buyCount),
                    second,
                    new ItemStack(sell, trade.sellCount),
                    0,
                    trade.maxUses,
                    0,
                    0.0f
            ));
        }
        return offers;
    }

    private static Item item(String id) {
        if (id == null || id.isBlank()) return null;
        return BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
    }

    private static final String DEFAULT_CONFIG = """
            {
              "requireSneak": false,
              "tables": {
                "default": [
                  { "buy": "minecraft:emerald", "buyCount": 1, "sell": "minecraft:bread", "sellCount": 2, "maxUses": 16 }
                ],
                "minecraft:cow": [
                  { "buy": "minecraft:wheat", "buyCount": 8, "sell": "minecraft:leather", "sellCount": 1, "maxUses": 8 }
                ],
                "minecraft:chicken": [
                  { "buy": "minecraft:wheat_seeds", "buyCount": 6, "sell": "minecraft:egg", "sellCount": 2, "maxUses": 12 }
                ],
                "minecraft:zombie": [
                  { "buy": "minecraft:rotten_flesh", "buyCount": 16, "sell": "minecraft:iron_ingot", "sellCount": 1, "maxUses": 4 }
                ],
                "minecraft:creeper": [
                  { "buy": "minecraft:emerald", "buyCount": 4, "sell": "minecraft:gunpowder", "sellCount": 6, "maxUses": 6 }
                ],
                "minecraft:enderman": [
                  { "buy": "minecraft:emerald", "buyCount": 8, "buyB": "minecraft:obsidian", "buyBCount": 1,
                    "sell": "minecraft:ender_pearl", "sellCount": 2, "maxUses": 4 }
                ]
              }
            }
            """;
}
