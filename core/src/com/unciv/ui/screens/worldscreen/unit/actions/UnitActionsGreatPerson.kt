package com.unciv.ui.screens.worldscreen.unit.actions

import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.UnitAction
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toPercent
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActionModifiers.getUseFrequency
import kotlin.math.min

@Suppress("UNUSED_PARAMETER") // references need to have the signature expected by UnitActions.actionTypeToFunctions
object UnitActionsGreatPerson {

    private const val GREAT_SCIENTIST_COOLDOWN_TURNS = 10

    internal fun getHurryResearchActions(unit: MapUnit, tile: Tile) = sequence {
        for (unique in unit.getMatchingUniques(UniqueType.CanHurryResearch)){
            val turnsSinceLastUse = unit.civ.gameInfo.turns - unit.greatScientistLastUsedTurn
            val onCooldown = turnsSinceLastUse < GREAT_SCIENTIST_COOLDOWN_TURNS
            val cooldownRemaining = GREAT_SCIENTIST_COOLDOWN_TURNS - turnsSinceLastUse
            val useFrequency = getUseFrequency(unit, unique, 76f)
            yield(UnitAction(
                UnitActionType.HurryResearch, useFrequency,
                title = if (onCooldown) "Hurry Research ([$cooldownRemaining] turns)"
                    else UnitActionType.HurryResearch.value,
                action = {
                    unit.civ.tech.completeCurrentTech()
                    unit.greatScientistLastUsedTurn = unit.civ.gameInfo.turns
                    unit.archimedesUpgradePoints++
                    unit.useMovementPoints(unit.currentMovement)
                }.takeIf {
                    !onCooldown
                        && unit.hasMovement()
                        && unit.civ.tech.currentTechnologyName() != null
                        && !unit.civ.tech.currentTechnology()!!.hasUnique(UniqueType.CannotBeHurried)
                }
            ))
        }
    }

    internal fun getHurryPolicyActions(unit: MapUnit, tile: Tile) = sequence {
        for (unique in unit.getMatchingUniques(UniqueType.CanHurryPolicy)){
            val useFrequency = getUseFrequency(unit, unique, 76f)
            yield(UnitAction(
                UnitActionType.HurryPolicy, useFrequency,
                action = {
                    unit.civ.policies.addCulture(unit.civ.policies.getCultureFromGreatWriter())
                    unit.consume()
                }.takeIf {unit.hasMovement()}
            ))
        }
    }

    internal fun getHurryWonderActions(unit: MapUnit, tile: Tile) = sequence {
        for (unique in unit.getMatchingUniques(UniqueType.CanSpeedupWonderConstruction)) {
            val canHurryWonder =
                if (!tile.isCityCenter()) false
                else tile.getCity()!!.cityConstructions.isBuildingWonder()
                    && tile.getCity()!!.cityConstructions.canBeHurried()
            val useFrequency = getUseFrequency(unit, unique, 75f)

            yield(UnitAction(
                UnitActionType.HurryWonder, useFrequency,
                action = {
                    tile.getCity()!!.cityConstructions.apply {
                        //http://civilization.wikia.com/wiki/Great_engineer_(Civ5)
                        addProductionPoints(((300 + 30 * tile.getCity()!!.population.population) * unit.civ.gameInfo.speed.productionCostModifier).toInt())
                        constructIfEnough()
                    }

                    unit.consume()
                }.takeIf { unit.hasMovement() && canHurryWonder }
            ))
        }
    }

    internal fun getHurryBuildingActions(unit: MapUnit, tile: Tile) = sequence {
        for (unique in unit.getMatchingUniques(UniqueType.CanSpeedupConstruction)) {
            val useFrequency = getUseFrequency(unit, unique, 75f)
            if (!tile.isCityCenter()) {
                yield(UnitAction(UnitActionType.HurryBuilding, useFrequency, action = null))
                continue
            }

            val cityConstructions = tile.getCity()!!.cityConstructions
            val canHurryConstruction = cityConstructions.getCurrentConstruction() is Building
                && cityConstructions.canBeHurried()

            //http://civilization.wikia.com/wiki/Great_engineer_(Civ5)
            val productionPointsToAdd = min(
                (300 + 30 * tile.getCity()!!.population.population) * unit.civ.gameInfo.speed.productionCostModifier,
                cityConstructions.getRemainingWork(cityConstructions. currentConstructionName()).toFloat() - 1
            ).toInt()
            if (productionPointsToAdd <= 0) continue

            yield(UnitAction(
                UnitActionType.HurryBuilding, useFrequency,
                title = "Hurry Construction (+[$productionPointsToAdd]⚙)",
                action = {
                    cityConstructions.apply {
                        addProductionPoints(productionPointsToAdd)
                        constructIfEnough()
                    }

                    unit.consume()
                }.takeIf { unit.hasMovement() && canHurryConstruction }
            ))
        }
    }

    internal fun getConductTradeMissionActions(unit: MapUnit, tile: Tile) = sequence {
        val canConductTradeMission = tile.owningCity?.civ?.isCityState == true
            && tile.owningCity?.civ != unit.civ
            && tile.owningCity?.civ?.isAtWarWith(unit.civ) == false
        for (unique in unit.getMatchingUniques(UniqueType.CanTradeWithCityStateForGoldAndInfluence)) {
            val influenceEarned = unique.params[0].toFloat()
            val useFrequency = getUseFrequency(unit, unique, 70f)

            yield(UnitAction(
                UnitActionType.ConductTradeMission, useFrequency,
                action = {
                    // http://civilization.wikia.com/wiki/Great_Merchant_(Civ5)
                    var goldEarned = (350 + 50 * unit.civ.getEraNumber()) * unit.civ.gameInfo.speed.goldCostModifier

                    // Apply the gold trade mission modifier
                    for (goldUnique in unit.getMatchingUniques(UniqueType.PercentGoldFromTradeMissions, checkCivInfoUniques = true))
                        goldEarned *= goldUnique.params[0].toPercent()

                    val goldEarnedInt = goldEarned.toInt()
                    unit.civ.addGold(goldEarnedInt)
                    val tileOwningCiv = tile.owningCity!!.civ

                    tileOwningCiv.getDiplomacyManager(unit.civ)!!.addInfluence(influenceEarned)
                    unit.civ.addNotification("Your trade mission to [$tileOwningCiv] has earned you [${goldEarnedInt.tr()}] gold and [${influenceEarned.tr()}] influence!",
                        NotificationCategory.General, tileOwningCiv.civName, NotificationIcon.Gold, NotificationIcon.Culture)
                    unit.consume()
                }.takeIf { unit.hasMovement() && canConductTradeMission }
            ))
        }
    }

    // region Archimedes skill system (Greece-unique Great Scientist)

    private const val ARCHIMEDES_PHYSICS_COOLDOWN = 8
    private const val ARCHIMEDES_MATH_COOLDOWN = 10
    private const val ARCHIMEDES_ENGINEERING_COOLDOWN = 12

    /** Shows a "Choose Skill" button when Archimedes has unspent upgrade points. */
    internal fun getChooseArchimedesSkillActions(unit: MapUnit, tile: Tile) = sequence {
        if (unit.baseUnit.name != "Great Scientist" || unit.archimedesUpgradePoints <= 0) return@sequence
        val physicsKnown = unit.promotions.promotions.contains("ArchimedesPhysics")
        val mathKnown = unit.promotions.promotions.contains("ArchimedesMath")
        val engineeringKnown = unit.promotions.promotions.contains("ArchimedesEngineering")
        val anyAvailable = !physicsKnown || !mathKnown || !engineeringKnown
        if (!anyAvailable) return@sequence
        val picks = sequenceOf(
            Triple("ArchimedesPhysics", "Physics", physicsKnown),
            Triple("ArchimedesMath", "Mathematics", mathKnown),
            Triple("ArchimedesEngineering", "Engineering", engineeringKnown)
        ).filter { !it.third }.toList()
        yield(UnitAction(
            UnitActionType.ChooseArchimedesSkill, 200f,
            title = "Choose Skill ([$${unit.archimedesUpgradePoints}])",
            action = {
                val choice = if (unit.civ.isAI()) picks.random(unit.civ.state.stateBasedRandom("ArchimedesSkill"))
                    else picks.first()  // Human: simplified, picks first available; a full picker UI could be added later
                unit.promotions.addPromotion(choice.first, isFree = true)
                unit.archimedesUpgradePoints -= 1
            }.takeIf { picks.isNotEmpty() }
        ))
    }

    /** Physics: grants combat buffs to nearby friendly military units for 3 turns. Cooldown 8. */
    internal fun getArchimedesPhysicsActions(unit: MapUnit, tile: Tile) = sequence {
        if (!unit.promotions.promotions.contains("ArchimedesPhysics")) return@sequence
        val turnsSinceLastUse = unit.civ.gameInfo.turns - unit.archimedesPhysicsLastUsedTurn
        val onCooldown = turnsSinceLastUse < ARCHIMEDES_PHYSICS_COOLDOWN
        val cooldownRemaining = ARCHIMEDES_PHYSICS_COOLDOWN - turnsSinceLastUse
        yield(UnitAction(
            UnitActionType.ArchimedesPhysics, 90f,
            title = if (onCooldown) "Physics ([$cooldownRemaining] turns)" else UnitActionType.ArchimedesPhysics.value,
            action = {
                for (nearbyTile in tile.getTilesInDistance(2)) {
                    for (otherUnit in nearbyTile.getUnits()) {
                        if (otherUnit.civ == unit.civ && otherUnit != unit && otherUnit.baseUnit.unitType.let { 
                                it == "Sword" || it == "Mounted" || it == "Archery" || it == "Siege" || 
                                it == "Ranged Water" || it == "Melee Water" }) {
                            otherUnit.setStatus("InspiredByArchimedes", 3)
                            if (otherUnit.baseUnit.unitType in listOf("Archery", "Siege", "Ranged Water"))
                                otherUnit.setStatus("ArchimedeanRange", 3)
                        }
                    }
                }
                unit.archimedesPhysicsLastUsedTurn = unit.civ.gameInfo.turns
                unit.useMovementPoints(unit.currentMovement)
            }.takeIf { !onCooldown && unit.hasMovement() }
        ))
    }

    /** Mathematics: instantly adds +300 Science to current research. Cooldown 10. */
    internal fun getArchimedesMathActions(unit: MapUnit, tile: Tile) = sequence {
        if (!unit.promotions.promotions.contains("ArchimedesMath")) return@sequence
        val turnsSinceLastUse = unit.civ.gameInfo.turns - unit.archimedesMathLastUsedTurn
        val onCooldown = turnsSinceLastUse < ARCHIMEDES_MATH_COOLDOWN
        val cooldownRemaining = ARCHIMEDES_MATH_COOLDOWN - turnsSinceLastUse
        yield(UnitAction(
            UnitActionType.ArchimedesMath, 90f,
            title = if (onCooldown) "Mathematics ([$cooldownRemaining] turns)" else UnitActionType.ArchimedesMath.value,
            action = {
                unit.civ.tech.addScience(300)
                unit.archimedesMathLastUsedTurn = unit.civ.gameInfo.turns
                unit.useMovementPoints(unit.currentMovement)
            }.takeIf { !onCooldown && unit.hasMovement() && unit.civ.tech.currentTechnologyName() != null }
        ))
    }

    /** Engineering: builds a Manufactory on current tile + adds +200 Production to current city. Cooldown 12. */
    internal fun getArchimedesEngineeringActions(unit: MapUnit, tile: Tile) = sequence {
        if (!unit.promotions.promotions.contains("ArchimedesEngineering")) return@sequence
        val turnsSinceLastUse = unit.civ.gameInfo.turns - unit.archimedesEngineeringLastUsedTurn
        val onCooldown = turnsSinceLastUse < ARCHIMEDES_ENGINEERING_COOLDOWN
        val cooldownRemaining = ARCHIMEDES_ENGINEERING_COOLDOWN - turnsSinceLastUse
        val city = tile.getCity()
        yield(UnitAction(
            UnitActionType.ArchimedesEngineering, 90f,
            title = if (onCooldown) "Engineering ([$cooldownRemaining] turns)" else UnitActionType.ArchimedesEngineering.value,
            action = {
                tile.setImprovement("Manufactory", unit.civ, unit)
                city?.cityConstructions?.addProductionPoints(200)
                unit.archimedesEngineeringLastUsedTurn = unit.civ.gameInfo.turns
                unit.useMovementPoints(unit.currentMovement)
            }.takeIf { !onCooldown && unit.hasMovement() && city != null }
        ))
    }

    // endregion
}
