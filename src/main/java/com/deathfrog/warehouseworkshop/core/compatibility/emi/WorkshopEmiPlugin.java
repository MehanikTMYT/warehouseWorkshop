package com.deathfrog.warehouseworkshop.core.compatibility.emi;

import java.util.List;

import com.deathfrog.warehouseworkshop.core.client.gui.modules.WindowWorkshopModule;
import com.ldtteam.blockui.BOScreen;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.stack.EmiStackInteraction;
import dev.emi.emi.api.widget.Bounds;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Native EMI entrypoint replacing the former {@code WorkshopJeiPlugin}. Warehouse Workshop does not
 * register any recipe category, workstation, or recipe of its own for the EMI browser -- it only ever
 * consumes existing vanilla {@code CraftingRecipe} and Domum Ornamentum {@code ArchitectsCutterRecipe}
 * entries, both of which their own respective plugins already make visible to EMI. This plugin only
 * needs to wire up screen-integration glue: an exclusion area (so EMI's own sidebar/search does not
 * overlap the workshop window -- the EMI equivalent of JEI's {@code IGuiHandlerRegistration}
 * bounds registration), a drag-drop handler for ghost ingredients (the EMI equivalent of JEI's
 * {@code IGhostIngredientHandler}, implemented in {@link WorkshopDragDropHandler}), and the same
 * shift-click-to-select-output mouse listener the JEI plugin used, now backed by
 * {@link EmiApi#getHoveredStack(boolean)} instead of JEI's ingredient list overlay.
 */
@EmiEntrypoint
public class WorkshopEmiPlugin implements EmiPlugin
{
    @Override
    public void register(final EmiRegistry registry)
    {
        registry.addExclusionArea(BOScreen.class, (screen, consumer) -> consumer.accept(getWindowBounds(screen)));
        registry.addDragDropHandler(BOScreen.class, WorkshopDragDropHandler.INSTANCE);

        NeoForge.EVENT_BUS.addListener(WorkshopEmiPlugin::onMouseButtonPressed);
    }

    /**
     * Computes the full workshop window's on-screen bounds so EMI can avoid drawing over it.
     *
     * @param gui the open BlockUI screen
     * @return the window's scaled screen-space bounds
     */
    private static Bounds getWindowBounds(final BOScreen gui)
    {
        final WindowWorkshopModule workshopWindow = WorkshopDragDropHandler.getWorkshopWindow(gui);
        if (workshopWindow == null)
        {
            return Bounds.EMPTY;
        }

        final WorkshopDragDropHandler.ScreenBounds bounds = WorkshopDragDropHandler.ScreenBounds.of(gui);
        final int guiWidth = Math.max(1, (int) Math.round(workshopWindow.getWidth() * bounds.scaleFactor()));
        final int guiHeight = Math.max(1, (int) Math.round(workshopWindow.getHeight() * bounds.scaleFactor()));
        return new Bounds(bounds.guiLeft(), bounds.guiTop(), guiWidth, guiHeight);
    }

    /**
     * Handles shift-click selection of an EMI-hovered item as the workshop's desired output, mirroring
     * the former JEI mouse listener that read {@code jeiRuntime.getIngredientListOverlay()}.
     *
     * @param event the mouse button event, cancelled when a shift-click selection is consumed
     */
    private static void onMouseButtonPressed(final ScreenEvent.MouseButtonPressed.Pre event)
    {
        if (event.getButton() != 0 || !(event.getScreen() instanceof final BOScreen gui))
        {
            return;
        }

        final WindowWorkshopModule workshopWindow = WorkshopDragDropHandler.getWorkshopWindow(gui);
        if (workshopWindow == null)
        {
            return;
        }

        final EmiStackInteraction interaction = EmiApi.getHoveredStack(true);
        if (interaction.isEmpty())
        {
            return;
        }

        final List<EmiStack> stacks = interaction.getStack().getEmiStacks();
        if (stacks.isEmpty())
        {
            return;
        }

        final ItemStack hoveredStack = stacks.get(0).getItemStack().copy();
        if (hoveredStack.isEmpty())
        {
            return;
        }

        if (Minecraft.getInstance().hasShiftDown())
        {
            workshopWindow.selectJeiOutput(hoveredStack);
            event.setCanceled(true);
        }
    }
}
