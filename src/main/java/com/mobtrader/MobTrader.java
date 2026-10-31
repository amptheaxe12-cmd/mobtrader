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
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;

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
        public int tradesPerMob = 3;
        public boolean sameTradesPerMob = true;
        public Map<String, List<Trade>> tables = new HashMap<>();
    }

    public static class Trade {
        public String buy;
        public int buyCount = 1;
        public int buyCountMax = 0;
        public String buyB;
        public int buyBCount = 1;
        public int buyBCountMax = 0;
        public String sell;
        public int sellCount = 1;
        public int sellCountMax = 0;
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
        List<Trade> pool = config.tables.get(id.toString());
        if (pool == null) pool = config.tables.get("default");
        if (pool == null || pool.isEmpty()) return offers;

        // Seeded from the mob's own UUID, so one particular cow always offers the
        // same thing while the next cow offers something else.
        Random random = config.sameTradesPerMob
                ? new Random(mob.getUUID().hashCode())
                : new Random();

        List<Trade> picked = new ArrayList<>(pool);
        Collections.shuffle(picked, random);

        int take = config.tradesPerMob <= 0
                ? picked.size()
                : Math.min(config.tradesPerMob, picked.size());

        for (Trade trade : picked.subList(0, take)) {
            Item buy = item(trade.buy);
            Item sell = item(trade.sell);
            if (buy == null || sell == null) continue;

            Optional<ItemCost> second = Optional.empty();
            if (trade.buyB != null && !trade.buyB.isBlank()) {
                Item buyB = item(trade.buyB);
                if (buyB != null) {
                    second = Optional.of(new ItemCost(buyB, roll(random, trade.buyBCount, trade.buyBCountMax)));
                }
            }

            offers.add(new MerchantOffer(
                    new ItemCost(buy, roll(random, trade.buyCount, trade.buyCountMax)),
                    second,
                    new ItemStack(sell, roll(random, trade.sellCount, trade.sellCountMax)),
                    0,
                    trade.maxUses,
                    0,
                    0.0f
            ));
        }
        return offers;
    }

    private static int roll(Random random, int min, int max) {
        int low = Math.max(1, min);
        if (max <= low) return low;
        return low + random.nextInt(max - low + 1);
    }

    /** Returns null for unknown ids, so a typo or a renamed item just drops that trade. */
    private static Item item(String id) {
        if (id == null || id.isBlank()) return null;
        Item found = BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
        return found == Items.AIR ? null : found;
    }

    private static final String DEFAULT_CONFIG = """
            {
              "requireSneak": false,
              "tradesPerMob": 3,
              "sameTradesPerMob": true,
              "tables": {
                "default": [
                  { "buy": "minecraft:emerald", "buyCount": 1, "buyCountMax": 3, "sell": "minecraft:bread", "sellCount": 2, "sellCountMax": 6 },
                  { "buy": "minecraft:emerald", "buyCount": 2, "buyCountMax": 5, "sell": "minecraft:torch", "sellCount": 4, "sellCountMax": 12 },
                  { "buy": "minecraft:coal", "buyCount": 8, "buyCountMax": 20, "sell": "minecraft:emerald", "sellCount": 1 },
                  { "buy": "minecraft:emerald", "buyCount": 3, "buyCountMax": 8, "sell": "minecraft:arrow", "sellCount": 8, "sellCountMax": 24 }
                ],

                "minecraft:cow": [
                  { "buy": "minecraft:wheat", "buyCount": 3, "buyCountMax": 9, "sell": "minecraft:leather", "sellCount": 1, "sellCountMax": 4 },
                  { "buy": "minecraft:wheat", "buyCount": 4, "buyCountMax": 10, "sell": "minecraft:beef", "sellCount": 2, "sellCountMax": 6 },
                  { "buy": "minecraft:wheat", "buyCount": 6, "buyCountMax": 14, "sell": "minecraft:milk_bucket", "sellCount": 1, "maxUses": 3 }
                ],
                "minecraft:mooshroom": [
                  { "buy": "minecraft:wheat", "buyCount": 2, "buyCountMax": 6, "sell": "minecraft:red_mushroom", "sellCount": 3, "sellCountMax": 9 },
                  { "buy": "minecraft:wheat", "buyCount": 3, "buyCountMax": 8, "sell": "minecraft:brown_mushroom", "sellCount": 3, "sellCountMax": 9 },
                  { "buy": "minecraft:wheat", "buyCount": 5, "buyCountMax": 12, "sell": "minecraft:mushroom_stew", "sellCount": 1, "sellCountMax": 2, "maxUses": 6 }
                ],
                "minecraft:sheep": [
                  { "buy": "minecraft:wheat", "buyCount": 1, "buyCountMax": 4, "sell": "minecraft:white_wool", "sellCount": 4, "sellCountMax": 12 },
                  { "buy": "minecraft:wheat", "buyCount": 2, "buyCountMax": 6, "sell": "minecraft:mutton", "sellCount": 2, "sellCountMax": 8 },
                  { "buy": "minecraft:wheat", "buyCount": 3, "buyCountMax": 8, "sell": "minecraft:black_wool", "sellCount": 2, "sellCountMax": 6 },
                  { "buy": "minecraft:wheat", "buyCount": 4, "buyCountMax": 10, "sell": "minecraft:shears", "sellCount": 1, "maxUses": 1 }
                ],
                "minecraft:chicken": [
                  { "buy": "minecraft:wheat_seeds", "buyCount": 2, "buyCountMax": 8, "sell": "minecraft:egg", "sellCount": 1, "sellCountMax": 5 },
                  { "buy": "minecraft:wheat_seeds", "buyCount": 3, "buyCountMax": 9, "sell": "minecraft:feather", "sellCount": 2, "sellCountMax": 7 },
                  { "buy": "minecraft:wheat_seeds", "buyCount": 5, "buyCountMax": 12, "sell": "minecraft:chicken", "sellCount": 1, "sellCountMax": 4 }
                ],
                "minecraft:pig": [
                  { "buy": "minecraft:carrot", "buyCount": 2, "buyCountMax": 7, "sell": "minecraft:porkchop", "sellCount": 2, "sellCountMax": 6 },
                  { "buy": "minecraft:potato", "buyCount": 3, "buyCountMax": 9, "sell": "minecraft:cooked_porkchop", "sellCount": 1, "sellCountMax": 4 },
                  { "buy": "minecraft:beetroot", "buyCount": 8, "buyCountMax": 20, "sell": "minecraft:saddle", "sellCount": 1, "maxUses": 1 }
                ],
                "minecraft:rabbit": [
                  { "buy": "minecraft:carrot", "buyCount": 2, "buyCountMax": 6, "sell": "minecraft:rabbit_hide", "sellCount": 2, "sellCountMax": 6 },
                  { "buy": "minecraft:dandelion", "buyCount": 2, "buyCountMax": 6, "sell": "minecraft:rabbit", "sellCount": 1, "sellCountMax": 3 },
                  { "buy": "minecraft:golden_carrot", "buyCount": 2, "buyCountMax": 5, "sell": "minecraft:rabbit_foot", "sellCount": 1, "maxUses": 3 }
                ],
                "minecraft:horse": [
                  { "buy": "minecraft:golden_apple", "buyCount": 1, "buyCountMax": 2, "sell": "minecraft:leather", "sellCount": 3, "sellCountMax": 8 },
                  { "buy": "minecraft:hay_block", "buyCount": 3, "buyCountMax": 8, "sell": "minecraft:saddle", "sellCount": 1, "maxUses": 1 },
                  { "buy": "minecraft:wheat", "buyCount": 6, "buyCountMax": 16, "sell": "minecraft:iron_horse_armor", "sellCount": 1, "maxUses": 1 }
                ],
                "minecraft:donkey": [
                  { "buy": "minecraft:wheat", "buyCount": 4, "buyCountMax": 10, "sell": "minecraft:leather", "sellCount": 2, "sellCountMax": 5 },
                  { "buy": "minecraft:hay_block", "buyCount": 2, "buyCountMax": 6, "sell": "minecraft:chest", "sellCount": 1, "sellCountMax": 3 }
                ],
                "minecraft:llama": [
                  { "buy": "minecraft:hay_block", "buyCount": 1, "buyCountMax": 4, "sell": "minecraft:leather", "sellCount": 2, "sellCountMax": 6 },
                  { "buy": "minecraft:wheat", "buyCount": 5, "buyCountMax": 12, "sell": "minecraft:white_carpet", "sellCount": 4, "sellCountMax": 10 },
                  { "buy": "minecraft:wheat", "buyCount": 8, "buyCountMax": 18, "sell": "minecraft:lead", "sellCount": 1, "maxUses": 2 }
                ],
                "minecraft:goat": [
                  { "buy": "minecraft:wheat", "buyCount": 3, "buyCountMax": 9, "sell": "minecraft:milk_bucket", "sellCount": 1, "maxUses": 3 },
                  { "buy": "minecraft:wheat", "buyCount": 10, "buyCountMax": 24, "sell": "minecraft:goat_horn", "sellCount": 1, "maxUses": 1 },
                  { "buy": "minecraft:wheat", "buyCount": 2, "buyCountMax": 7, "sell": "minecraft:bone", "sellCount": 2, "sellCountMax": 6 }
                ],
                "minecraft:bee": [
                  { "buy": "minecraft:poppy", "buyCount": 2, "buyCountMax": 6, "sell": "minecraft:honeycomb", "sellCount": 1, "sellCountMax": 4 },
                  { "buy": "minecraft:dandelion", "buyCount": 3, "buyCountMax": 8, "sell": "minecraft:honey_bottle", "sellCount": 1, "sellCountMax": 3 },
                  { "buy": "minecraft:sunflower", "buyCount": 4, "buyCountMax": 10, "sell": "minecraft:honey_block", "sellCount": 1, "maxUses": 4 }
                ],
                "minecraft:wolf": [
                  { "buy": "minecraft:bone", "buyCount": 2, "buyCountMax": 8, "sell": "minecraft:leather", "sellCount": 1, "sellCountMax": 4 },
                  { "buy": "minecraft:beef", "buyCount": 2, "buyCountMax": 6, "sell": "minecraft:string", "sellCount": 2, "sellCountMax": 6 },
                  { "buy": "minecraft:bone", "buyCount": 4, "buyCountMax": 12, "sell": "minecraft:bone_meal", "sellCount": 4, "sellCountMax": 12 }
                ],
                "minecraft:cat": [
                  { "buy": "minecraft:cod", "buyCount": 1, "buyCountMax": 4, "sell": "minecraft:string", "sellCount": 2, "sellCountMax": 6 },
                  { "buy": "minecraft:salmon", "buyCount": 2, "buyCountMax": 6, "sell": "minecraft:feather", "sellCount": 1, "sellCountMax": 4 },
                  { "buy": "minecraft:cod", "buyCount": 5, "buyCountMax": 14, "sell": "minecraft:rabbit_foot", "sellCount": 1, "maxUses": 2 }
                ],
                "minecraft:fox": [
                  { "buy": "minecraft:sweet_berries", "buyCount": 2, "buyCountMax": 8, "sell": "minecraft:glow_berries", "sellCount": 2, "sellCountMax": 6 },
                  { "buy": "minecraft:sweet_berries", "buyCount": 6, "buyCountMax": 16, "sell": "minecraft:emerald", "sellCount": 1, "sellCountMax": 2 },
                  { "buy": "minecraft:chicken", "buyCount": 2, "buyCountMax": 5, "sell": "minecraft:leather", "sellCount": 1, "sellCountMax": 3 }
                ],
                "minecraft:panda": [
                  { "buy": "minecraft:bamboo", "buyCount": 4, "buyCountMax": 16, "sell": "minecraft:slime_ball", "sellCount": 1, "sellCountMax": 3 },
                  { "buy": "minecraft:bamboo", "buyCount": 8, "buyCountMax": 24, "sell": "minecraft:cake", "sellCount": 1, "maxUses": 3 },
                  { "buy": "minecraft:cake", "buyCount": 1, "sell": "minecraft:bamboo", "sellCount": 16, "sellCountMax": 32 }
                ],
                "minecraft:polar_bear": [
                  { "buy": "minecraft:salmon", "buyCount": 2, "buyCountMax": 8, "sell": "minecraft:cod", "sellCount": 3, "sellCountMax": 9 },
                  { "buy": "minecraft:cod", "buyCount": 4, "buyCountMax": 12, "sell": "minecraft:ice", "sellCount": 2, "sellCountMax": 8 },
                  { "buy": "minecraft:salmon", "buyCount": 8, "buyCountMax": 20, "sell": "minecraft:packed_ice", "sellCount": 1, "sellCountMax": 4 }
                ],
                "minecraft:turtle": [
                  { "buy": "minecraft:seagrass", "buyCount": 3, "buyCountMax": 10, "sell": "minecraft:kelp", "sellCount": 4, "sellCountMax": 12 },
                  { "buy": "minecraft:seagrass", "buyCount": 6, "buyCountMax": 16, "sell": "minecraft:sand", "sellCount": 8, "sellCountMax": 20 },
                  { "buy": "minecraft:seagrass", "buyCount": 12, "buyCountMax": 30, "sell": "minecraft:turtle_scute", "sellCount": 1, "maxUses": 2 }
                ],
                "minecraft:squid": [
                  { "buy": "minecraft:cod", "buyCount": 1, "buyCountMax": 5, "sell": "minecraft:ink_sac", "sellCount": 2, "sellCountMax": 8 },
                  { "buy": "minecraft:kelp", "buyCount": 6, "buyCountMax": 16, "sell": "minecraft:black_dye", "sellCount": 3, "sellCountMax": 9 }
                ],
                "minecraft:glow_squid": [
                  { "buy": "minecraft:cod", "buyCount": 1, "buyCountMax": 5, "sell": "minecraft:glow_ink_sac", "sellCount": 1, "sellCountMax": 5 },
                  { "buy": "minecraft:kelp", "buyCount": 8, "buyCountMax": 20, "sell": "minecraft:glowstone_dust", "sellCount": 2, "sellCountMax": 8 }
                ],
                "minecraft:dolphin": [
                  { "buy": "minecraft:cod", "buyCount": 2, "buyCountMax": 8, "sell": "minecraft:prismarine_shard", "sellCount": 1, "sellCountMax": 4 },
                  { "buy": "minecraft:salmon", "buyCount": 6, "buyCountMax": 16, "sell": "minecraft:nautilus_shell", "sellCount": 1, "maxUses": 2 }
                ],
                "minecraft:strider": [
                  { "buy": "minecraft:warped_fungus", "buyCount": 1, "buyCountMax": 4, "sell": "minecraft:string", "sellCount": 2, "sellCountMax": 8 },
                  { "buy": "minecraft:warped_fungus", "buyCount": 4, "buyCountMax": 12, "sell": "minecraft:blaze_rod", "sellCount": 1, "maxUses": 3 },
                  { "buy": "minecraft:warped_fungus", "buyCount": 8, "buyCountMax": 20, "sell": "minecraft:lava_bucket", "sellCount": 1, "maxUses": 2 }
                ],

                "minecraft:zombie": [
                  { "buy": "minecraft:rotten_flesh", "buyCount": 8, "buyCountMax": 24, "sell": "minecraft:iron_ingot", "sellCount": 1, "sellCountMax": 3 },
                  { "buy": "minecraft:rotten_flesh", "buyCount": 4, "buyCountMax": 14, "sell": "minecraft:carrot", "sellCount": 2, "sellCountMax": 8 },
                  { "buy": "minecraft:rotten_flesh", "buyCount": 4, "buyCountMax": 14, "sell": "minecraft:potato", "sellCount": 2, "sellCountMax": 8 },
                  { "buy": "minecraft:rotten_flesh", "buyCount": 16, "buyCountMax": 40, "sell": "minecraft:iron_shovel", "sellCount": 1, "maxUses": 1 }
                ],
                "minecraft:husk": [
                  { "buy": "minecraft:rotten_flesh", "buyCount": 6, "buyCountMax": 18, "sell": "minecraft:sand", "sellCount": 8, "sellCountMax": 24 },
                  { "buy": "minecraft:rotten_flesh", "buyCount": 10, "buyCountMax": 26, "sell": "minecraft:sandstone", "sellCount": 4, "sellCountMax": 12 },
                  { "buy": "minecraft:rotten_flesh", "buyCount": 12, "buyCountMax": 30, "sell": "minecraft:iron_ingot", "sellCount": 1, "sellCountMax": 2 }
                ],
                "minecraft:drowned": [
                  { "buy": "minecraft:rotten_flesh", "buyCount": 8, "buyCountMax": 22, "sell": "minecraft:copper_ingot", "sellCount": 2, "sellCountMax": 8 },
                  { "buy": "minecraft:rotten_flesh", "buyCount": 14, "buyCountMax": 36, "sell": "minecraft:nautilus_shell", "sellCount": 1, "maxUses": 2 },
                  { "buy": "minecraft:rotten_flesh", "buyCount": 6, "buyCountMax": 18, "sell": "minecraft:prismarine_shard", "sellCount": 2, "sellCountMax": 6 }
                ],
                "minecraft:skeleton": [
                  { "buy": "minecraft:bone", "buyCount": 2, "buyCountMax": 8, "sell": "minecraft:arrow", "sellCount": 4, "sellCountMax": 16 },
                  { "buy": "minecraft:bone", "buyCount": 4, "buyCountMax": 14, "sell": "minecraft:bone_meal", "sellCount": 3, "sellCountMax": 12 },
                  { "buy": "minecraft:bone", "buyCount": 12, "buyCountMax": 32, "sell": "minecraft:bow", "sellCount": 1, "maxUses": 1 }
                ],
                "minecraft:stray": [
                  { "buy": "minecraft:bone", "buyCount": 3, "buyCountMax": 10, "sell": "minecraft:arrow", "sellCount": 4, "sellCountMax": 14 },
                  { "buy": "minecraft:bone", "buyCount": 6, "buyCountMax": 16, "sell": "minecraft:packed_ice", "sellCount": 1, "sellCountMax": 4 },
                  { "buy": "minecraft:bone", "buyCount": 16, "buyCountMax": 40, "sell": "minecraft:blue_ice", "sellCount": 1, "maxUses": 2 }
                ],
                "minecraft:wither_skeleton": [
                  { "buy": "minecraft:bone", "buyCount": 6, "buyCountMax": 18, "sell": "minecraft:coal", "sellCount": 4, "sellCountMax": 12 },
                  { "buy": "minecraft:coal", "buyCount": 12, "buyCountMax": 32, "sell": "minecraft:wither_skeleton_skull", "sellCount": 1, "maxUses": 1 },
                  { "buy": "minecraft:bone", "buyCount": 10, "buyCountMax": 26, "sell": "minecraft:soul_sand", "sellCount": 4, "sellCountMax": 12 }
                ],
                "minecraft:creeper": [
                  { "buy": "minecraft:gunpowder", "buyCount": 2, "buyCountMax": 8, "sell": "minecraft:firework_rocket", "sellCount": 2, "sellCountMax": 8 },
                  { "buy": "minecraft:gunpowder", "buyCount": 6, "buyCountMax": 18, "sell": "minecraft:tnt", "sellCount": 1, "sellCountMax": 3, "maxUses": 4 },
                  { "buy": "minecraft:gunpowder", "buyCount": 4, "buyCountMax": 12, "sell": "minecraft:flint_and_steel", "sellCount": 1, "maxUses": 2 }
                ],
                "minecraft:spider": [
                  { "buy": "minecraft:string", "buyCount": 3, "buyCountMax": 10, "sell": "minecraft:spider_eye", "sellCount": 1, "sellCountMax": 4 },
                  { "buy": "minecraft:string", "buyCount": 6, "buyCountMax": 16, "sell": "minecraft:cobweb", "sellCount": 1, "sellCountMax": 4 },
                  { "buy": "minecraft:spider_eye", "buyCount": 2, "buyCountMax": 6, "sell": "minecraft:fermented_spider_eye", "sellCount": 1, "sellCountMax": 2 }
                ],
                "minecraft:cave_spider": [
                  { "buy": "minecraft:string", "buyCount": 4, "buyCountMax": 12, "sell": "minecraft:fermented_spider_eye", "sellCount": 1, "sellCountMax": 3 },
                  { "buy": "minecraft:string", "buyCount": 8, "buyCountMax": 20, "sell": "minecraft:cobweb", "sellCount": 2, "sellCountMax": 6 },
                  { "buy": "minecraft:spider_eye", "buyCount": 3, "buyCountMax": 9, "sell": "minecraft:poisonous_potato", "sellCount": 2, "sellCountMax": 6 }
                ],
                "minecraft:enderman": [
                  { "buy": "minecraft:ender_pearl", "buyCount": 1, "buyCountMax": 3, "sell": "minecraft:grass_block", "sellCount": 4, "sellCountMax": 16 },
                  { "buy": "minecraft:ender_pearl", "buyCount": 2, "buyCountMax": 6, "sell": "minecraft:obsidian", "sellCount": 1, "sellCountMax": 5 },
                  { "buy": "minecraft:ender_pearl", "buyCount": 3, "buyCountMax": 9, "sell": "minecraft:diamond", "sellCount": 1, "sellCountMax": 3, "maxUses": 4 },
                  { "buy": "minecraft:ender_pearl", "buyCount": 8, "buyCountMax": 20, "sell": "minecraft:ender_chest", "sellCount": 1, "maxUses": 1 }
                ],
                "minecraft:slime": [
                  { "buy": "minecraft:slime_ball", "buyCount": 2, "buyCountMax": 8, "sell": "minecraft:sticky_piston", "sellCount": 1, "sellCountMax": 3 },
                  { "buy": "minecraft:slime_ball", "buyCount": 4, "buyCountMax": 12, "sell": "minecraft:lead", "sellCount": 1, "sellCountMax": 2 },
                  { "buy": "minecraft:slime_ball", "buyCount": 6, "buyCountMax": 16, "sell": "minecraft:magma_cream", "sellCount": 2, "sellCountMax": 6 }
                ],
                "minecraft:magma_cube": [
                  { "buy": "minecraft:magma_cream", "buyCount": 2, "buyCountMax": 6, "sell": "minecraft:fire_charge", "sellCount": 2, "sellCountMax": 6 },
                  { "buy": "minecraft:magma_cream", "buyCount": 3, "buyCountMax": 9, "sell": "minecraft:blaze_powder", "sellCount": 2, "sellCountMax": 6 },
                  { "buy": "minecraft:magma_cream", "buyCount": 6, "buyCountMax": 16, "sell": "minecraft:magma_block", "sellCount": 4, "sellCountMax": 12 }
                ],
                "minecraft:blaze": [
                  { "buy": "minecraft:blaze_rod", "buyCount": 1, "buyCountMax": 3, "sell": "minecraft:fire_charge", "sellCount": 3, "sellCountMax": 9 },
                  { "buy": "minecraft:blaze_rod", "buyCount": 2, "buyCountMax": 6, "sell": "minecraft:glowstone_dust", "sellCount": 4, "sellCountMax": 12 },
                  { "buy": "minecraft:blaze_rod", "buyCount": 4, "buyCountMax": 10, "sell": "minecraft:magma_cream", "sellCount": 2, "sellCountMax": 6 }
                ],
                "minecraft:ghast": [
                  { "buy": "minecraft:ghast_tear", "buyCount": 1, "buyCountMax": 2, "sell": "minecraft:fire_charge", "sellCount": 4, "sellCountMax": 12 },
                  { "buy": "minecraft:ghast_tear", "buyCount": 1, "buyCountMax": 3, "sell": "minecraft:gunpowder", "sellCount": 4, "sellCountMax": 16 },
                  { "buy": "minecraft:ghast_tear", "buyCount": 2, "buyCountMax": 5, "sell": "minecraft:soul_sand", "sellCount": 6, "sellCountMax": 16 }
                ],
                "minecraft:witch": [
                  { "buy": "minecraft:glass_bottle", "buyCount": 2, "buyCountMax": 8, "sell": "minecraft:redstone", "sellCount": 4, "sellCountMax": 16 },
                  { "buy": "minecraft:glass_bottle", "buyCount": 3, "buyCountMax": 10, "sell": "minecraft:glowstone_dust", "sellCount": 3, "sellCountMax": 12 },
                  { "buy": "minecraft:spider_eye", "buyCount": 4, "buyCountMax": 12, "sell": "minecraft:brewing_stand", "sellCount": 1, "maxUses": 1 }
                ],
                "minecraft:piglin": [
                  { "buy": "minecraft:gold_ingot", "buyCount": 1, "buyCountMax": 4, "sell": "minecraft:ender_pearl", "sellCount": 1, "sellCountMax": 4 },
                  { "buy": "minecraft:gold_ingot", "buyCount": 2, "buyCountMax": 6, "sell": "minecraft:obsidian", "sellCount": 1, "sellCountMax": 4 },
                  { "buy": "minecraft:gold_ingot", "buyCount": 3, "buyCountMax": 8, "sell": "minecraft:crying_obsidian", "sellCount": 1, "sellCountMax": 3 },
                  { "buy": "minecraft:gold_ingot", "buyCount": 1, "buyCountMax": 5, "sell": "minecraft:fire_charge", "sellCount": 1, "sellCountMax": 4 }
                ],
                "minecraft:zombified_piglin": [
                  { "buy": "minecraft:gold_nugget", "buyCount": 6, "buyCountMax": 20, "sell": "minecraft:gold_ingot", "sellCount": 1, "sellCountMax": 2 },
                  { "buy": "minecraft:gold_nugget", "buyCount": 4, "buyCountMax": 14, "sell": "minecraft:rotten_flesh", "sellCount": 4, "sellCountMax": 12 },
                  { "buy": "minecraft:gold_ingot", "buyCount": 4, "buyCountMax": 12, "sell": "minecraft:golden_sword", "sellCount": 1, "maxUses": 1 }
                ],
                "minecraft:hoglin": [
                  { "buy": "minecraft:crimson_fungus", "buyCount": 1, "buyCountMax": 4, "sell": "minecraft:porkchop", "sellCount": 2, "sellCountMax": 8 },
                  { "buy": "minecraft:crimson_fungus", "buyCount": 3, "buyCountMax": 9, "sell": "minecraft:leather", "sellCount": 2, "sellCountMax": 6 },
                  { "buy": "minecraft:crimson_fungus", "buyCount": 5, "buyCountMax": 14, "sell": "minecraft:cooked_porkchop", "sellCount": 2, "sellCountMax": 6 }
                ],
                "minecraft:guardian": [
                  { "buy": "minecraft:prismarine_shard", "buyCount": 2, "buyCountMax": 8, "sell": "minecraft:prismarine_crystals", "sellCount": 1, "sellCountMax": 4 },
                  { "buy": "minecraft:prismarine_shard", "buyCount": 4, "buyCountMax": 12, "sell": "minecraft:cod", "sellCount": 3, "sellCountMax": 9 },
                  { "buy": "minecraft:prismarine_shard", "buyCount": 12, "buyCountMax": 30, "sell": "minecraft:sponge", "sellCount": 1, "maxUses": 2 }
                ],
                "minecraft:elder_guardian": [
                  { "buy": "minecraft:prismarine_shard", "buyCount": 8, "buyCountMax": 20, "sell": "minecraft:sponge", "sellCount": 1, "sellCountMax": 3 },
                  { "buy": "minecraft:prismarine_crystals", "buyCount": 4, "buyCountMax": 12, "sell": "minecraft:sea_lantern", "sellCount": 1, "sellCountMax": 4 },
                  { "buy": "minecraft:prismarine_shard", "buyCount": 16, "buyCountMax": 40, "sell": "minecraft:heart_of_the_sea", "sellCount": 1, "maxUses": 1 }
                ],
                "minecraft:phantom": [
                  { "buy": "minecraft:phantom_membrane", "buyCount": 1, "buyCountMax": 3, "sell": "minecraft:feather", "sellCount": 4, "sellCountMax": 12 },
                  { "buy": "minecraft:phantom_membrane", "buyCount": 2, "buyCountMax": 6, "sell": "minecraft:leather", "sellCount": 2, "sellCountMax": 8 },
                  { "buy": "minecraft:phantom_membrane", "buyCount": 4, "buyCountMax": 12, "sell": "minecraft:glass", "sellCount": 8, "sellCountMax": 24 }
                ],
                "minecraft:iron_golem": [
                  { "buy": "minecraft:poppy", "buyCount": 2, "buyCountMax": 8, "sell": "minecraft:iron_nugget", "sellCount": 4, "sellCountMax": 16 },
                  { "buy": "minecraft:poppy", "buyCount": 8, "buyCountMax": 20, "sell": "minecraft:iron_ingot", "sellCount": 1, "sellCountMax": 3 },
                  { "buy": "minecraft:iron_ingot", "buyCount": 6, "buyCountMax": 16, "sell": "minecraft:iron_block", "sellCount": 1, "maxUses": 4 }
                ],
                "minecraft:snow_golem": [
                  { "buy": "minecraft:snowball", "buyCount": 4, "buyCountMax": 16, "sell": "minecraft:snow_block", "sellCount": 2, "sellCountMax": 8 },
                  { "buy": "minecraft:snowball", "buyCount": 8, "buyCountMax": 24, "sell": "minecraft:ice", "sellCount": 2, "sellCountMax": 8 },
                  { "buy": "minecraft:carved_pumpkin", "buyCount": 1, "buyCountMax": 3, "sell": "minecraft:snowball", "sellCount": 8, "sellCountMax": 24 }
                ],
                "minecraft:shulker": [
                  { "buy": "minecraft:ender_pearl", "buyCount": 4, "buyCountMax": 12, "sell": "minecraft:shulker_shell", "sellCount": 1, "sellCountMax": 2, "maxUses": 3 },
                  { "buy": "minecraft:ender_pearl", "buyCount": 2, "buyCountMax": 8, "sell": "minecraft:purpur_block", "sellCount": 4, "sellCountMax": 12 },
                  { "buy": "minecraft:ender_pearl", "buyCount": 1, "buyCountMax": 4, "sell": "minecraft:chorus_fruit", "sellCount": 2, "sellCountMax": 8 }
                ],
                "minecraft:pillager": [
                  { "buy": "minecraft:emerald", "buyCount": 2, "buyCountMax": 8, "sell": "minecraft:arrow", "sellCount": 8, "sellCountMax": 24 },
                  { "buy": "minecraft:emerald", "buyCount": 10, "buyCountMax": 24, "sell": "minecraft:crossbow", "sellCount": 1, "maxUses": 1 }
                ],
                "minecraft:vindicator": [
                  { "buy": "minecraft:emerald", "buyCount": 8, "buyCountMax": 20, "sell": "minecraft:iron_axe", "sellCount": 1, "maxUses": 1 },
                  { "buy": "minecraft:emerald", "buyCount": 3, "buyCountMax": 10, "sell": "minecraft:iron_ingot", "sellCount": 1, "sellCountMax": 3 }
                ],
                "minecraft:evoker": [
                  { "buy": "minecraft:emerald", "buyCount": 24, "buyCountMax": 48, "sell": "minecraft:totem_of_undying", "sellCount": 1, "maxUses": 1 },
                  { "buy": "minecraft:emerald", "buyCount": 4, "buyCountMax": 12, "sell": "minecraft:emerald_block", "sellCount": 1, "maxUses": 2 }
                ],
                "minecraft:warden": [
                  { "buy": "minecraft:echo_shard", "buyCount": 1, "buyCountMax": 3, "sell": "minecraft:sculk", "sellCount": 4, "sellCountMax": 16 },
                  { "buy": "minecraft:echo_shard", "buyCount": 2, "buyCountMax": 6, "sell": "minecraft:sculk_catalyst", "sellCount": 1, "maxUses": 2 },
                  { "buy": "minecraft:echo_shard", "buyCount": 4, "buyCountMax": 10, "sell": "minecraft:diamond", "sellCount": 2, "sellCountMax": 6, "maxUses": 3 }
                ]
              }
            }
            """;
}
