package com.deathfrog.warehouseworkshop.core.compatibility.emi;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.deathfrog.warehouseworkshop.core.client.gui.modules.WindowWorkshopModule;
import com.deathfrog.warehouseworkshop.core.client.gui.modules.WindowWorkshopModule.WorkshopTargetArea;
import com.ldtteam.blockui.BOScreen;
import com.ldtteam.blockui.views.BOWindow;

import dev.emi.emi.api.EmiDragDropHandler;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.Bounds;
import net.minecraft.world.item.ItemStack;

/**
 * EMI equivalent of the former JEI {@code IGhostIngredientHandler<BOScreen>}. Exposes the same nine
 * crafting-grid slots plus the output preview slot as drag-drop targets on the workshop window,
 * delegating acceptance to the same public methods {@link WindowWorkshopModule} already exposed for
 * the JEI integration ({@code getJeiIngredientTargetArea}/{@code getJeiOutputTargetArea}/
 * {@code canAcceptIngredientInSlot}/{@code applyJeiIngredientToSlot}/{@code selectJeiOutput}) -- those
 * methods are generic screen-space target/accept plumbing with "Jei" only in the name, not an API
 * dependency, so they needed no changes for this swap.
 * <p>
 * UX note: EMI's {@link EmiDragDropHandler.BoundsBased} highlights every registered bounds
 * simultaneously while a stack is being dragged (a translucent green fill), rather than only the
 * bounds currently under the cursor. This is a native EMI rendering choice (it has no per-target
 * highlight color hook like JEI's {@code IGhostIngredientHandler.Target}), not a simulated or
 * degraded feature.
 */
public final class WorkshopDragDropHandler implements EmiDragDropHandler<BOScreen>
{
    private static final int GRID_SIZE = 9;

    private WorkshopDragDropHandler()
    {
    }

    static final WorkshopDragDropHandler INSTANCE = new WorkshopDragDropHandler();

    /**
     * Builds the bounds -> accept-callback map for the currently open workshop window, if any.
     *
     * @param gui the BlockUI screen being dragged over
     * @return a map of scaled screen bounds to their drop callbacks
     */
    private static Map<Bounds, Consumer<EmiIngredient>> targetsFor(final BOScreen gui)
    {
        final Map<Bounds, Consumer<EmiIngredient>> targets = new HashMap<>();
        final WindowWorkshopModule workshopWindow = getWorkshopWindow(gui);
        if (workshopWindow == null)
        {
            return targets;
        }

        for (int slot = 0; slot < GRID_SIZE; slot++)
        {
            final int targetSlot = slot;
            final WorkshopTargetArea targetArea = workshopWindow.getJeiIngredientTargetArea(targetSlot);
            if (targetArea == null || !workshopWindow.canAcceptIngredientInSlot(targetSlot))
            {
                continue;
            }

            targets.put(getScaledArea(gui, targetArea), ingredient -> applyIngredient(workshopWindow, ingredient, stack -> workshopWindow.applyJeiIngredientToSlot(targetSlot, stack)));
        }

        final WorkshopTargetArea outputTargetArea = workshopWindow.getJeiOutputTargetArea();
        targets.put(getScaledArea(gui, outputTargetArea), ingredient -> applyIngredient(workshopWindow, ingredient, workshopWindow::selectJeiOutput));

        return targets;
    }

    @Override
    public boolean dropStack(final BOScreen screen, final EmiIngredient stack, final int x, final int y)
    {
        for (final Map.Entry<Bounds, Consumer<EmiIngredient>> entry : targetsFor(screen).entrySet())
        {
            if (entry.getKey().contains(x, y))
            {
                entry.getValue().accept(stack);
                return true;
            }
        }

        return false;
    }

    /**
     * Extracts the first concrete {@link ItemStack} from an EMI ingredient and forwards it to the
     * workshop window, mirroring how JEI's {@code ITypedIngredient} was unwrapped previously.
     *
     * @param workshopWindow the workshop window receiving the drop
     * @param ingredient the dropped EMI ingredient
     * @param consumer the workshop window callback to invoke with the resolved item stack
     */
    private static void applyIngredient(final WindowWorkshopModule workshopWindow, final EmiIngredient ingredient, final Consumer<ItemStack> consumer)
    {
        final List<EmiStack> stacks = ingredient.getEmiStacks();
        if (stacks.isEmpty())
        {
            return;
        }

        final ItemStack itemStack = stacks.get(0).getItemStack();
        if (!itemStack.isEmpty())
        {
            consumer.accept(itemStack.copy());
        }
    }

    static WindowWorkshopModule getWorkshopWindow(final BOScreen gui)
    {
        final BOWindow window = gui.getWindow();
        return window instanceof WindowWorkshopModule workshopWindow ? workshopWindow : null;
    }

    static Bounds getScaledArea(final BOScreen gui, final WorkshopTargetArea area)
    {
        final ScreenBounds bounds = ScreenBounds.of(gui);
        final int x = bounds.guiLeft() + scale(bounds.scaleFactor(), area.x());
        final int y = bounds.guiTop() + scale(bounds.scaleFactor(), area.y());
        final int width = Math.max(1, scale(bounds.scaleFactor(), area.width()));
        final int height = Math.max(1, scale(bounds.scaleFactor(), area.height()));
        return new Bounds(x, y, width, height);
    }

    private static int scale(final double factor, final int value)
    {
        return (int) Math.round(value * factor);
    }

    record ScreenBounds(int guiLeft, int guiTop, double scaleFactor)
    {
        static ScreenBounds of(final BOScreen gui)
        {
            final BOWindow window = gui.getWindow();
            final double scaleFactor = gui.getRenderScale() / gui.getVanillaGuiScale();
            final int guiWidth = Math.max(gui.getFramebufferWidth(), 320);
            final int guiHeight = Math.max(gui.getFramebufferHeight(), 240);
            final int guiLeft = (int) Math.round((guiWidth - (window.getWidth() * gui.getRenderScale())) / (2.0D * gui.getVanillaGuiScale()));
            final int guiTop = (int) Math.round((guiHeight - (window.getHeight() * gui.getRenderScale())) / (2.0D * gui.getVanillaGuiScale()));
            return new ScreenBounds(guiLeft, guiTop, scaleFactor);
        }
    }
}
