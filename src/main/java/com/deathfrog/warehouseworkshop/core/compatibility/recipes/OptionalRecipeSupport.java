package com.deathfrog.warehouseworkshop.core.compatibility.recipes;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import com.minecolonies.api.crafting.VanillaRecipeUtils;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapedRecipe;

/**
 * Soft integrations for optional recipe implementations from other mods.
 */
public final class OptionalRecipeSupport
{
    private static final Identifier UNIQUE_TAG_SHAPELESS_SERIALIZER_ID =
        Identifier.fromNamespaceAndPath("mctradepost", "unique_tag_shapeless");

    private OptionalRecipeSupport()
    {
        // Utility class.
    }

    public static List<CraftingSlotRequirement> buildCraftingSlotRequirements(final CraftingRecipe recipe, final int gridSize)
    {
        final Optional<UniqueTagRecipeDefinition> uniqueTagRecipe = tryGetUniqueTagRecipe(recipe);
        if (uniqueTagRecipe.isPresent())
        {
            return buildUniqueTagRequirements(uniqueTagRecipe.get(), gridSize);
        }

        return buildDefaultRequirements(recipe, gridSize);
    }

    private static List<CraftingSlotRequirement> buildDefaultRequirements(final CraftingRecipe recipe, final int gridSize)
    {
        final List<CraftingSlotRequirement> slots = new ArrayList<>(Collections.nCopies(gridSize, CraftingSlotRequirement.empty()));

        // Since 26.1, Recipe no longer exposes a generic getIngredients() (see
        // com.minecolonies.api.crafting.VanillaRecipeUtils's own javadoc on the same removal).
        // ShapedRecipe kept its own getIngredients() but it now returns one Optional<Ingredient>
        // per dense width*height slot (empty slots are Optional.empty()) instead of a compacted
        // list, so the shaped branch below unwraps that directly for exact positional accuracy.
        // Every other CraftingRecipe (ShapelessRecipe included -- it lost its own getIngredients()
        // entirely) falls back to VanillaRecipeUtils.getIngredients(recipe), which reads
        // recipe.placementInfo().ingredients() -- the same flat, order-preserving list
        // ShapelessRecipe#getIngredients() used to return directly.
        if (recipe instanceof final ShapedRecipe shapedRecipe)
        {
            final int width = shapedRecipe.getWidth();
            final int height = shapedRecipe.getHeight();
            final List<Optional<Ingredient>> shapedIngredients = shapedRecipe.getIngredients();
            for (int y = 0; y < height; y++)
            {
                for (int x = 0; x < width; x++)
                {
                    final Optional<Ingredient> ingredient = shapedIngredients.get((y * width) + x);
                    if (ingredient.isPresent())
                    {
                        slots.set((y * 3) + x, new CraftingSlotRequirement(ingredient.get(), null));
                    }
                }
            }
        }
        else
        {
            final List<Ingredient> ingredients = VanillaRecipeUtils.getIngredients(recipe);
            for (int i = 0; i < Math.min(gridSize, ingredients.size()); i++)
            {
                slots.set(i, new CraftingSlotRequirement(ingredients.get(i), null));
            }
        }

        return slots;
    }

    private static List<CraftingSlotRequirement> buildUniqueTagRequirements(final UniqueTagRecipeDefinition recipe, final int gridSize)
    {
        final List<CraftingSlotRequirement> slots = new ArrayList<>(Collections.nCopies(gridSize, CraftingSlotRequirement.empty()));
        if (gridSize <= 0)
        {
            return slots;
        }

        slots.set(0, new CraftingSlotRequirement(recipe.base(), null));
        for (int i = 1; i < Math.min(gridSize, recipe.count() + 1); i++)
        {
            slots.set(i, new CraftingSlotRequirement(recipe.tagIngredient(), recipe.uniqueGroup()));
        }

        return slots;
    }

    /**
     * Tries to extract a unique tag recipe definition from the given recipe.
     * <p>
     * This method is used to support soft integrations with other mods that use a custom shapeless recipe serializer.
     * <p>
     * The method first checks if the recipe's serializer is the custom shapeless recipe serializer.
     * If it is, the method then tries to reflectively call the recipe's "getBase", "getTag", and "getCount" methods.
     * If all of these calls succeed, the method then wraps the returned values in a {@link UniqueTagRecipeDefinition} and returns it.
     * If any of the calls fail, the method returns an empty optional.
     * <p>
     * The custom shapeless recipe serializer is identified by the resource location "mctradepost:unique_tag_shapeless".
     * The "getBase", "getTag", and "getCount" methods are expected to return an {@link Ingredient}, a {@link TagKey}, and an {@link Integer}, respectively.
     * The base ingredient is the ingredient that the tag is applied to.
     * The tag ingredient is the ingredient that the tag is applied to.
     * The count is the number of times to apply the tag to the base ingredient.
     * <p>
     * The returned {@link UniqueTagRecipeDefinition} contains the base ingredient, the tag ingredient, the count, and the resource location of the tag.
     * @param recipe the recipe to extract the unique tag recipe definition from
     * @return an optional containing the extracted unique tag recipe definition, or an empty optional if the extraction fails
     */
    private static Optional<UniqueTagRecipeDefinition> tryGetUniqueTagRecipe(final CraftingRecipe recipe)
    {
        RecipeSerializer<?> serializer = recipe.getSerializer();
        
        if (serializer == null)
        {
            return Optional.empty();
        }

        final Identifier serializerId = BuiltInRegistries.RECIPE_SERIALIZER.getKey(serializer);
        if (!UNIQUE_TAG_SHAPELESS_SERIALIZER_ID.equals(serializerId))
        {
            return Optional.empty();
        }

        try
        {
            final Method getBase = recipe.getClass().getMethod("getBase");
            final Method getTag = recipe.getClass().getMethod("getTag");
            final Method getCount = recipe.getClass().getMethod("getCount");

            final Object baseValue = getBase.invoke(recipe);
            final Object tagValue = getTag.invoke(recipe);
            final Object countValue = getCount.invoke(recipe);

            if (!(baseValue instanceof Ingredient base)
                || !(tagValue instanceof TagKey<?> rawTag)
                || !(countValue instanceof Integer count)
                || count.intValue() <= 0)
            {
                return Optional.empty();
            }

            @SuppressWarnings("unchecked")
            final TagKey<Item> tag = (TagKey<Item>) rawTag;
            // Ingredient.of(TagKey) no longer exists as of 26.1.2; resolve the tag to its Holder
            // first, matching the pattern mctradepost's own UniqueTagShapelessRecipe.getIngredients()
            // already uses for the same problem.
            final Ingredient tagIngredient = Ingredient.of(BuiltInRegistries.ITEM.getOrThrow(tag));
            if (tagIngredient.isEmpty())
            {
                return Optional.empty();
            }

            return Optional.of(new UniqueTagRecipeDefinition(base, tagIngredient, count.intValue(), tag.location()));
        }
        catch (final ReflectiveOperationException | RuntimeException ignored)
        {
            return Optional.empty();
        }
    }

    /**
     * A single crafting-grid slot's ingredient requirement.
     * <p>
     * {@code Ingredient.EMPTY} no longer exists as of 26.1 -- {@code Ingredient}'s constructor now
     * throws {@code UnsupportedOperationException} for an empty value set, so an empty ingredient is
     * simply not constructible anymore (matching how {@code ShapedRecipe#getIngredients()} itself now
     * represents blank grid cells as {@code Optional.empty()} rather than a sentinel Ingredient
     * instance). {@code ingredient} is therefore {@code null} for an empty slot; use {@link #isEmpty()}
     * rather than calling {@code ingredient().isEmpty()} directly.
     */
    public record CraftingSlotRequirement(@Nullable Ingredient ingredient, @Nullable Identifier uniqueGroup)
    {
        public static CraftingSlotRequirement empty()
        {
            return new CraftingSlotRequirement(null, null);
        }

        public boolean isEmpty()
        {
            return ingredient == null;
        }
    }

    private record UniqueTagRecipeDefinition(
        Ingredient base,
        Ingredient tagIngredient,
        int count,
        Identifier uniqueGroup)
    {
    }
}
