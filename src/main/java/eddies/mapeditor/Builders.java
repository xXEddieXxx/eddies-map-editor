package eddies.mapeditor;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import lombok.experimental.UtilityClass;
import org.triplea.map.data.elements.AttachmentList;
import org.triplea.map.data.elements.AttachmentList.Attachment;
import org.triplea.map.data.elements.AttachmentList.Attachment.Option;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.GamePlay;
import org.triplea.map.data.elements.GamePlay.Sequence;
import org.triplea.map.data.elements.GamePlay.Sequence.Step;
import org.triplea.map.data.elements.Initialize;
import org.triplea.map.data.elements.Initialize.OwnerInitialize;
import org.triplea.map.data.elements.Initialize.OwnerInitialize.TerritoryOwner;
import org.triplea.map.data.elements.Initialize.RelationshipInitialize;
import org.triplea.map.data.elements.Initialize.RelationshipInitialize.Relationship;
import org.triplea.map.data.elements.Initialize.ResourceInitialize;
import org.triplea.map.data.elements.Initialize.ResourceInitialize.ResourceGiven;
import org.triplea.map.data.elements.Initialize.UnitInitialize;
import org.triplea.map.data.elements.Initialize.UnitInitialize.UnitPlacement;
import org.triplea.map.data.elements.Map;
import org.triplea.map.data.elements.Map.Connection;
import org.triplea.map.data.elements.Map.Territory;
import org.triplea.map.data.elements.PlayerList;
import org.triplea.map.data.elements.Production;
import org.triplea.map.data.elements.Production.PlayerProduction;
import org.triplea.map.data.elements.Production.ProductionFrontier;
import org.triplea.map.data.elements.Production.ProductionRule;
import org.triplea.map.data.elements.Production.Rule.Cost;
import org.triplea.map.data.elements.Production.Rule.Result;
import org.triplea.map.data.elements.PropertyList;
import org.triplea.map.data.elements.PropertyList.Property;
import org.triplea.map.data.elements.RelationshipTypes;
import org.triplea.map.data.elements.Technology;
import org.triplea.map.data.elements.Technology.PlayerTech;
import org.triplea.map.data.elements.Technology.PlayerTech.Category;
import org.triplea.map.data.elements.Technology.Technologies;

/**
 * {@code toBuilder()} for the parts of the XML model the editor changes, as extension methods
 * ({@code @ExtensionMethod(Builders.class)}): a builder filled with every field of the value, so a
 * change copies the rest. A TripleA version whose model has its own {@code toBuilder()} uses that
 * one; older ones, without it, these. Public, as Lombok only takes public static methods.
 */
@UtilityClass
public class Builders {

  public static Game.GameBuilder toBuilder(final Game value) {
    return filled(value, Game.builder());
  }

  public static Map.MapBuilder toBuilder(final Map value) {
    return filled(value, Map.builder());
  }

  public static Territory.TerritoryBuilder toBuilder(final Territory value) {
    return filled(value, Territory.builder());
  }

  public static Connection.ConnectionBuilder toBuilder(final Connection value) {
    return filled(value, Connection.builder());
  }

  public static PlayerList.PlayerListBuilder toBuilder(final PlayerList value) {
    return filled(value, PlayerList.builder());
  }

  public static Production.ProductionBuilder toBuilder(final Production value) {
    return filled(value, Production.builder());
  }

  public static ProductionRule.ProductionRuleBuilder toBuilder(final ProductionRule value) {
    return filled(value, ProductionRule.builder());
  }

  public static Cost.CostBuilder toBuilder(final Cost value) {
    return filled(value, Cost.builder());
  }

  public static ProductionFrontier.ProductionFrontierBuilder toBuilder(
      final ProductionFrontier value) {
    return filled(value, ProductionFrontier.builder());
  }

  public static AttachmentList.AttachmentListBuilder toBuilder(final AttachmentList value) {
    return filled(value, AttachmentList.builder());
  }

  public static Attachment.AttachmentBuilder toBuilder(final Attachment value) {
    return filled(value, Attachment.builder());
  }

  public static Option.OptionBuilder toBuilder(final Option value) {
    return filled(value, Option.builder());
  }

  public static Initialize.InitializeBuilder toBuilder(final Initialize value) {
    return filled(value, Initialize.builder());
  }

  public static OwnerInitialize.OwnerInitializeBuilder toBuilder(final OwnerInitialize value) {
    return filled(value, OwnerInitialize.builder());
  }

  public static TerritoryOwner.TerritoryOwnerBuilder toBuilder(final TerritoryOwner value) {
    return filled(value, TerritoryOwner.builder());
  }

  public static UnitInitialize.UnitInitializeBuilder toBuilder(final UnitInitialize value) {
    return filled(value, UnitInitialize.builder());
  }

  public static UnitPlacement.UnitPlacementBuilder toBuilder(final UnitPlacement value) {
    return filled(value, UnitPlacement.builder());
  }

  public static ResourceInitialize.ResourceInitializeBuilder toBuilder(
      final ResourceInitialize value) {
    return filled(value, ResourceInitialize.builder());
  }

  public static ResourceGiven.ResourceGivenBuilder toBuilder(final ResourceGiven value) {
    return filled(value, ResourceGiven.builder());
  }

  public static RelationshipInitialize.RelationshipInitializeBuilder toBuilder(
      final RelationshipInitialize value) {
    return filled(value, RelationshipInitialize.builder());
  }

  public static PropertyList.PropertyListBuilder toBuilder(final PropertyList value) {
    return filled(value, PropertyList.builder());
  }

  public static Property.PropertyBuilder toBuilder(final Property value) {
    return filled(value, Property.builder());
  }

  public static GamePlay.GamePlayBuilder toBuilder(final GamePlay value) {
    return filled(value, GamePlay.builder());
  }

  public static Sequence.SequenceBuilder toBuilder(final Sequence value) {
    return filled(value, Sequence.builder());
  }

  public static Step.StepBuilder toBuilder(final Step value) {
    return filled(value, Step.builder());
  }

  public static Relationship.RelationshipBuilder toBuilder(final Relationship value) {
    return filled(value, Relationship.builder());
  }

  public static RelationshipTypes.RelationshipTypesBuilder toBuilder(
      final RelationshipTypes value) {
    return filled(value, RelationshipTypes.builder());
  }

  public static Result.ResultBuilder toBuilder(final Result value) {
    return filled(value, Result.builder());
  }

  public static PlayerProduction.PlayerProductionBuilder toBuilder(
      final PlayerProduction value) {
    return filled(value, PlayerProduction.builder());
  }

  public static Technology.TechnologyBuilder toBuilder(final Technology value) {
    return filled(value, Technology.builder());
  }

  public static Technologies.TechnologiesBuilder toBuilder(final Technologies value) {
    return filled(value, Technologies.builder());
  }

  public static PlayerTech.PlayerTechBuilder toBuilder(final PlayerTech value) {
    return filled(value, PlayerTech.builder());
  }

  public static Category.CategoryBuilder toBuilder(final Category value) {
    return filled(value, Category.builder());
  }

  /**
   * The builder with each field of the value set through the builder's method of the field's name,
   * as Lombok's {@code toBuilder()} does.
   *
   * @throws IllegalStateException for a field the builder has no method for: it would be lost
   */
  static <B> B filled(final Object value, final B builder) {
    for (final Field field : value.getClass().getDeclaredFields()) {
      if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
        continue;
      }
      final Method setter =
          Arrays.stream(builder.getClass().getMethods())
              .filter(m -> m.getName().equals(field.getName()) && m.getParameterCount() == 1)
              .findFirst()
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          value.getClass().getSimpleName()
                              + "."
                              + field.getName()
                              + " has no builder method, so a change would lose it"));
      try {
        field.setAccessible(true);
        setter.invoke(builder, field.get(value));
      } catch (final IllegalAccessException | InvocationTargetException e) {
        throw new IllegalStateException("Could not copy " + field, e);
      }
    }
    return builder;
  }
}
