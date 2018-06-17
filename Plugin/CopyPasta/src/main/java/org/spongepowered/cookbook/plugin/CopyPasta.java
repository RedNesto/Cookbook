package org.spongepowered.cookbook.plugin;

import static org.spongepowered.api.command.args.GenericArguments.seq;
import static org.spongepowered.api.command.args.GenericArguments.string;

import com.flowpowered.math.vector.Vector3i;
import com.google.common.collect.Maps;
import com.google.inject.Inject;
import org.slf4j.Logger;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.block.BlockSnapshot;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.spec.CommandSpec;
import org.spongepowered.api.config.ConfigDir;
import org.spongepowered.api.data.DataContainer;
import org.spongepowered.api.data.persistence.DataFormats;
import org.spongepowered.api.data.persistence.DataTranslators;
import org.spongepowered.api.data.type.HandTypes;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.block.InteractBlockEvent;
import org.spongepowered.api.event.filter.cause.Root;
import org.spongepowered.api.event.game.state.GamePreInitializationEvent;
import org.spongepowered.api.item.ItemTypes;
import org.spongepowered.api.item.inventory.ItemStack;
import org.spongepowered.api.plugin.Plugin;
import org.spongepowered.api.plugin.PluginContainer;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.format.TextColors;
import org.spongepowered.api.world.BlockChangeFlags;
import org.spongepowered.api.world.extent.ArchetypeVolume;
import org.spongepowered.api.world.schematic.BlockPaletteTypes;
import org.spongepowered.api.world.schematic.Schematic;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@Plugin(id = CopyPasta.PLUGIN_ID, name = "CopyPasta", version = "0.4", description = "An example of the schematic API.")
public class CopyPasta {

    public static final String PLUGIN_ID = "copypasta";

    @Inject
    private Logger logger;
    @Inject
    private PluginContainer plugin;
    @Inject
    @ConfigDir(sharedRoot = false)
    private File config;

    //We store players in a map using UUIDs to allow player objects to be garbage collected.
    private final Map<UUID, PlayerData> player_data = Maps.newHashMap();
    private File schematicsDir;

    private PlayerData get(Player pl) {
        PlayerData data = this.player_data.get(pl.getUniqueId());
        if (data == null) {
            data = new PlayerData(pl.getUniqueId());
            this.player_data.put(pl.getUniqueId(), data);
        }
        return data;
    }

    @Listener
    public void onGamePreInitialization(GamePreInitializationEvent event) {
        this.schematicsDir = new File(this.config, "schematics");
        this.schematicsDir.mkdirs();
        this.logger.info("Saving schematics to " + this.schematicsDir.getAbsolutePath());
        Sponge.getCommandManager().register(this, CommandSpec.builder()
                .description(Text.of("Copies a region of the world to your clipboard"))
                .permission(PLUGIN_ID + ".command.copy")
                .executor((src, args) -> {
                    if (!(src instanceof Player)) {
                        src.sendMessage(Text.of(TextColors.RED, "Player only."));
                        return CommandResult.success();
                    }
                    Player player = (Player) src;
                    PlayerData data = get(player);
                    if (data.getPos1() == null || data.getPos2() == null) {
                        player.sendMessage(Text.of(TextColors.RED, "You must set both positions before copying"));
                        return CommandResult.success();
                    }
                    Vector3i min = data.getPos1().min(data.getPos2());
                    Vector3i max = data.getPos1().max(data.getPos2());

                    // Defines the volume we will be copying, using the min
                    // and max values gotten from the interact events.
                    ArchetypeVolume volume = player.getWorld().createArchetypeVolume(min, max, player.getLocation().getPosition().toInt());
                    data.setClipboard(volume);
                    player.sendMessage(Text.of(TextColors.GREEN, "Saved to clipboard."));
                    return CommandResult.success();
                })
                .build(), "copy");
        Sponge.getCommandManager().register(this, CommandSpec.builder()
                .description(Text.of("Pastes your clipboard at your current position"))
                .permission(PLUGIN_ID + ".command.paste")
                .executor((src, args) -> {
                    if (!(src instanceof Player)) {
                        src.sendMessage(Text.of(TextColors.RED, "Player only."));
                        return CommandResult.success();
                    }
                    Player player = (Player) src;
                    PlayerData data = get(player);
                    ArchetypeVolume volume = data.getClipboard();
                    if (volume == null) {
                        player.sendMessage(Text.of(TextColors.RED, "You must copy something before pasting"));
                        return CommandResult.success();
                    }

                    // Here we paste in the volume that we have previously
                    // copied or loaded. We specify that we want all block changes (update neighbor, observers and physics)
                    volume.apply(player.getLocation(), BlockChangeFlags.ALL);
                    player.sendMessage(Text.of(TextColors.GREEN, "Pasted clipboard into world."));
                    return CommandResult.success();
                })
                .build(), "paste");
        Sponge.getCommandManager().register(this, CommandSpec.builder()
                .description(Text.of("Saves your clipboard to disk"))
                .permission(PLUGIN_ID + ".command.save")
                .arguments(seq(string(Text.of("format")), string(Text.of("name"))))
                .executor((src, args) -> {
                    if (!(src instanceof Player)) {
                        src.sendMessage(Text.of(TextColors.RED, "Player only."));
                        return CommandResult.success();
                    }
                    String format = args.getOne("format").get().toString();
                    String name = args.getOne("name").get().toString();
                    Player player = (Player) src;
                    PlayerData data = get(player);
                    ArchetypeVolume volume = data.getClipboard();
                    if (volume == null) {
                        player.sendMessage(Text.of(TextColors.RED, "You must copy something before saving"));
                        return CommandResult.success();
                    }
                    if (!"legacy".equalsIgnoreCase(format) && !"sponge".equalsIgnoreCase(format)) {
                        player.sendMessage(Text.of(TextColors.RED, "Unsupported schematic format, supported formats are [legacy, sponge]"));
                        return CommandResult.success();
                    }

                    // Here we create the schematic object, set it's values,
                    // volume, and palette. The palette defines how the blocks
                    // are saved.

                    Schematic schematic = Schematic.builder()
                            .volume(data.getClipboard())
                            .metaValue(Schematic.METADATA_AUTHOR, player.getName())
                            .metaValue(Schematic.METADATA_NAME, name)
                            .paletteType(BlockPaletteTypes.LOCAL)
                            .build();

                    // We need to serialize the Schematic to a DataContainer so
                    // that we can save it using one of the DataFormats.
                    DataContainer schematicData = null;
                    if ("legacy".equalsIgnoreCase(format)) {
                        schematicData = DataTranslators.LEGACY_SCHEMATIC.translate(schematic);
                    } else if ("sponge".equalsIgnoreCase(format)) {
                        schematicData = DataTranslators.SCHEMATIC.translate(schematic);
                    }
                    File outputFile = new File(this.schematicsDir, name + ".schematic");
                    try {

                        // Normally we save NBT files as NBT files. We do this by using the NBT DataFormat.
                        DataFormats.NBT.writeTo(new GZIPOutputStream(new FileOutputStream(outputFile)), schematicData);
                        player.sendMessage(Text.of(TextColors.GREEN, "Saved schematic to " + outputFile.getAbsolutePath()));
                    } catch (Exception e) {
                        e.printStackTrace();
                        player.sendMessage(Text.of(TextColors.DARK_RED, "Error saving schematic: " + e.getMessage()));
                        return CommandResult.success();
                    }
                    return CommandResult.success();
                })
                .build(), "save");
        Sponge.getCommandManager().register(this, CommandSpec.builder()
                .description(Text.of("Loads a schematic from disk to your clipboard"))
                .permission(PLUGIN_ID + ".command.load")
                .arguments(seq(string(Text.of("format")), string(Text.of("name"))))
                .executor((src, args) -> {
                    if (!(src instanceof Player)) {
                        src.sendMessage(Text.of(TextColors.RED, "Player only."));
                        return CommandResult.success();
                    }
                    String format = args.getOne("format").get().toString();
                    String name = args.getOne("name").get().toString();
                    Player player = (Player) src;
                    PlayerData data = get(player);
                    if (!"legacy".equalsIgnoreCase(format) && !"sponge".equalsIgnoreCase(format)) {
                        player.sendMessage(Text.of(TextColors.RED, "Unsupported schematic format, supported formats are [legacy, sponge]"));
                        return CommandResult.success();
                    }
                    File inputFile = new File(this.schematicsDir, name + ".schematic");
                    if (!inputFile.exists()) {
                        player.sendMessage(Text.of(TextColors.RED, "Schematic at " + inputFile.getAbsolutePath() + " not found."));
                        return CommandResult.success();
                    }
                    DataContainer schematicData = null;
                    try {
                        // Schematics and normally saved as NBT, so we use the
                        // NBT DataFormat to read the data.
                        schematicData = DataFormats.NBT.readFrom(new GZIPInputStream(new FileInputStream(inputFile)));
                    } catch (Exception e) {
                        e.printStackTrace();
                        player.sendMessage(Text.of(TextColors.DARK_RED, "Error loading schematic: " + e.getMessage()));
                        return CommandResult.success();
                    }
                    Schematic schematic = null;

                    // We need to deserialize the DataContainer to a Schematic before we cam use it.
                    if ("legacy".equalsIgnoreCase(format)) {
                        schematic = DataTranslators.LEGACY_SCHEMATIC.translate(schematicData);
                    } else if ("sponge".equalsIgnoreCase(format)) {
                        schematic = DataTranslators.SCHEMATIC.translate(schematicData);
                    }
                    player.sendMessage(Text.of(TextColors.GREEN, "Loaded schematic from " + inputFile.getAbsolutePath()));
                    data.setClipboard(schematic);
                    return CommandResult.success();
                })
                .build(), "load");
    }

    @Listener
    public void onInteract(InteractBlockEvent.Secondary.MainHand event, @Root Player player) {
        Optional<ItemStack> item = player.getItemInHand(HandTypes.MAIN_HAND);
        if (item.isPresent() && item.get().getType().equals(ItemTypes.WOODEN_AXE) && event.getTargetBlock() != BlockSnapshot.NONE) {
            get(player).setPos2(event.getTargetBlock().getPosition());
            player.sendMessage(Text.of(TextColors.LIGHT_PURPLE, "Position 2 set to " + event.getTargetBlock().getPosition()));
            event.setCancelled(true);
        }
    }

    @Listener
    public void onInteract(InteractBlockEvent.Primary.MainHand event, @Root Player player) {
        Optional<ItemStack> item = player.getItemInHand(HandTypes.MAIN_HAND);
        if (item.isPresent() && item.get().getType().equals(ItemTypes.WOODEN_AXE)) {
            get(player).setPos1(event.getTargetBlock().getPosition());
            player.sendMessage(Text.of(TextColors.LIGHT_PURPLE, "Position 1 set to " + event.getTargetBlock().getPosition()));
            event.setCancelled(true);
        }
    }

    public static class PlayerData {

        private final UUID uid;
        private Vector3i pos1;
        private Vector3i pos2;
        private ArchetypeVolume clipboard;

        public PlayerData(UUID uid) {
            this.uid = uid;
        }

        public UUID getUid() {
            return this.uid;
        }

        public Vector3i getPos1() {
            return this.pos1;
        }

        public void setPos1(Vector3i pos) {
            this.pos1 = pos;
        }

        public Vector3i getPos2() {
            return this.pos2;
        }

        public void setPos2(Vector3i pos) {
            this.pos2 = pos;
        }

        public ArchetypeVolume getClipboard() {
            return this.clipboard;
        }

        public void setClipboard(ArchetypeVolume volume) {
            this.clipboard = volume;
        }
    }
}
