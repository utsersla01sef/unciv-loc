package com.unciv.ui.screens.worldscreen.unit.actions

import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.UnitAction
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toPercent
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActionModifiers.getUseFrequency
import kotlin.math.min
import yairm210.purity.annotations.Readonly

@Suppress("UNUSED_PARAMETER") // references need to have the signature expected by UnitActions.actionTypeToFunctions
object UnitActionsGreatPerson {

    private const val GREAT_SCIENTIST_COOLDOWN_TURNS = 10

    internal fun getHurryResearchActions(unit: MapUnit, tile: Tile) = sequence {
        for (unique in unit.getMatchingUniques(UniqueType.CanHurryResearch)){
            val turnsSinceLastUse = unit.civ.gameInfo.turns - unit.greatScientistLastUsedTurn
            val onCooldown = turnsSinceLastUse < GREAT_SCIENTIST_COOLDOWN_TURNS
            val cooldownRemaining = GREAT_SCIENTIST_COOLDOWN_TURNS - turnsSinceLastUse
            val useFrequency = getUseFrequency(unit, unique, 76f)
            val isNaturalPhilosopher = unit.baseUnit.name == "Natural Philosopher"
            yield(UnitAction(
                UnitActionType.HurryResearch, useFrequency,
                title = when {
                    onCooldown -> "Hurry Research ([$cooldownRemaining] turns)"
                    isNaturalPhilosopher -> "Hurry Research (+[1] skill point)"
                    else -> UnitActionType.HurryResearch.value
                },
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

                    if (unit.baseUnit.name != "Natural Philosopher") unit.consume()
                    else unit.useMovementPoints(unit.currentMovement)
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

                    if (unit.baseUnit.name != "Natural Philosopher") unit.consume()
                    else unit.useMovementPoints(unit.currentMovement)
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
    private const val ARCHIMEDES_SHIP_COOLDOWN = 15
    private const val ARCHIMEDES_SIEGE_COOLDOWN = 12

    /** Shows "Learn X" buttons when Archimedes has unspent upgrade points - one per available skill, with effect descriptions. */
    internal fun getChooseArchimedesSkillActions(unit: MapUnit, tile: Tile) = sequence {
        if (unit.baseUnit.name != "Natural Philosopher" || unit.archimedesUpgradePoints <= 0) return@sequence
        val known = unit.promotions.promotions
        val picks = sequenceOf(
            Triple("ArchimedesPhysics", "Learn Physics ([+1] Sight, reveal [3] tiles)", "ArchimedesPhysics" !in known),
            Triple("ArchimedesMath", "Learn Mathematics ([+2] Movement, +[300] Science)", "ArchimedesMath" !in known),
            Triple("ArchimedesEngineering", "Learn Engineering (terraform, Citadel, ships, [+200] Production)", "ArchimedesEngineering" !in known)
        ).filter { it.third }.toList()
        for ((promotion, title, _) in picks) {
            yield(UnitAction(
                UnitActionType.ChooseArchimedesSkill, 200f,
                title = title,
                action = {
                    unit.promotions.addPromotion(promotion, isFree = true)
                    unit.archimedesUpgradePoints -= 1
                    unit.civ.addNotification("Your [Natural Philosopher] has mastered a new skill!",
                        NotificationCategory.Units, NotificationIcon.Science)
                }
            ))
        }
    }

    /** Physics: grants combat buffs to nearby friendly military units for 3 turns. Cooldown 8. */
    internal fun getArchimedesPhysicsActions(unit: MapUnit, tile: Tile) = sequence {
        if (!unit.promotions.promotions.contains("ArchimedesPhysics")) return@sequence
        val turnsSinceLastUse = unit.civ.gameInfo.turns - unit.archimedesPhysicsLastUsedTurn
        val onCooldown = turnsSinceLastUse < ARCHIMEDES_PHYSICS_COOLDOWN
        val cooldownRemaining = ARCHIMEDES_PHYSICS_COOLDOWN - turnsSinceLastUse
        yield(UnitAction(
            UnitActionType.ArchimedesPhysics, 90f,
            title = if (onCooldown) "Physics ([$cooldownRemaining] turns)" else "Physics (rally troops, [3] turns)",
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
            title = if (onCooldown) "Mathematics ([$cooldownRemaining] turns)" else "Mathematics (+[300] Science)",
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
            title = if (onCooldown) "Engineering ([$cooldownRemaining] turns)" else "Engineering (Manufactory, [+200] Production)",
            action = {
                tile.setImprovement("Manufactory", unit.civ, unit)
                city?.cityConstructions?.addProductionPoints(200)
                unit.archimedesEngineeringLastUsedTurn = unit.civ.gameInfo.turns
                unit.useMovementPoints(unit.currentMovement)
            }.takeIf { !onCooldown && unit.hasMovement() && city != null }
        ))
    }

    /** Engineering shipyard: construct any ship the civ can currently build, on an adjacent water tile. One button per ship. Cooldown 15. */
    internal fun getArchimedesShipyardActions(unit: MapUnit, tile: Tile) = sequence {
        if (unit.baseUnit.name != "Natural Philosopher") return@sequence
        if (!unit.promotions.promotions.contains("ArchimedesEngineering")) return@sequence
        val turnsSinceLastUse = unit.civ.gameInfo.turns - unit.archimedesShipLastUsedTurn
        val onCooldown = turnsSinceLastUse < ARCHIMEDES_SHIP_COOLDOWN
        val cooldownRemaining = ARCHIMEDES_SHIP_COOLDOWN - turnsSinceLastUse
        if (onCooldown) {
            yield(UnitAction(UnitActionType.ArchimedesShipyard, 88f,
                title = "Construct Ship ([$cooldownRemaining] turns)"))
            return@sequence
        }
        val waterTile = if (tile.isWater) tile else tile.neighbors.firstOrNull { it.isWater }
        for (ship in getConstructibleShips(unit)) {
            yield(UnitAction(
                UnitActionType.ArchimedesShipyard, 88f,
                title = "Construct Ship ([${ship.name}], [-90]% maintenance)",
                action = {
                    val target = if (tile.isWater) tile
                        else tile.neighbors.firstOrNull { it.isWater } ?: return@UnitAction
                    val placedUnit = unit.civ.units.placeUnitNearTile(target.position.toHexCoord(), ship.name)
                        ?: return@UnitAction  // placement can fail (e.g. ice-blocked) - stay quiet, no cost
                    placedUnit.archimedesConstructed = true
                    unit.archimedesShipLastUsedTurn = unit.civ.gameInfo.turns
                    unit.useMovementPoints(unit.currentMovement)
                    unit.civ.addNotification("Your [Natural Philosopher] has constructed a [${ship.name}]!",
                        NotificationCategory.Units, ship.name)
                }.takeIf { unit.hasMovement() && waterTile != null }
            ))
        }
    }

    /** All ships this civ can currently build - water units, great people excluded, best first. */
    @Readonly
    private fun getConstructibleShips(unit: MapUnit): List<BaseUnit> {
        val civ = unit.civ
        return getConstructibleUnits(unit) { it.unitType.endsWith("Water") }
            .sortedByDescending { techColumn(unit, it) }
    }

    /** All siege engines this civ can currently build, best first. */
    @Readonly
    private fun getConstructibleSiegeEngines(unit: MapUnit): List<BaseUnit> {
        return getConstructibleUnits(unit) { it.unitType == "Siege" }
            .sortedByDescending { techColumn(unit, it) }
    }

    /** Common filter for Archimedes workshop construction: not great people, tech known, not obsolete, unique fits, resources affordable. */
    @Readonly
    private fun getConstructibleUnits(unit: MapUnit, typeFilter: (BaseUnit) -> Boolean): List<BaseUnit> {
        val civ = unit.civ
        return civ.gameInfo.ruleset.units.values.asSequence()
            .filter(typeFilter)
            .filter { !it.isGreatPerson }
            .filter { it.uniqueTo == null || civ.matchesFilter(it.uniqueTo!!) }
            .filter { it.requiredTech == null || civ.tech.isResearched(it.requiredTech!!) }
            .filter { !civ.tech.isObsolete(it) }
            .filter { baseUnit ->
                baseUnit.getResourceRequirementsPerTurn(civ.state).none {
                    it.value > 0 && civ.getResourceAmount(it.key) < it.value
                }
            }
            .toList()
    }

    @Readonly
    private fun techColumn(unit: MapUnit, baseUnit: BaseUnit): Int =
        unit.civ.gameInfo.ruleset.technologies[baseUnit.requiredTech]?.column?.columnNumber ?: -1

    /** Engineering siege workshop: construct any siege engine the civ can currently build, near the current tile. One button per engine. Cooldown 12. */
    internal fun getArchimedesSiegeActions(unit: MapUnit, tile: Tile) = sequence {
        if (unit.baseUnit.name != "Natural Philosopher") return@sequence
        if (!unit.promotions.promotions.contains("ArchimedesEngineering")) return@sequence
        val turnsSinceLastUse = unit.civ.gameInfo.turns - unit.archimedesSiegeLastUsedTurn
        val onCooldown = turnsSinceLastUse < ARCHIMEDES_SIEGE_COOLDOWN
        val cooldownRemaining = ARCHIMEDES_SIEGE_COOLDOWN - turnsSinceLastUse
        if (onCooldown) {
            yield(UnitAction(UnitActionType.ArchimedesSiege, 87f,
                title = "Construct Siege Engine ([$cooldownRemaining] turns)"))
            return@sequence
        }
        for (engine in getConstructibleSiegeEngines(unit)) {
            yield(UnitAction(
                UnitActionType.ArchimedesSiege, 87f,
                title = "Construct Siege Engine ([${engine.name}], [-90]% maintenance)",
                action = {
                    val placedUnit = unit.civ.units.placeUnitNearTile(tile.position.toHexCoord(), engine.name)
                        ?: return@UnitAction  // placement can fail - stay quiet, no cost
                    placedUnit.archimedesConstructed = true
                    unit.archimedesSiegeLastUsedTurn = unit.civ.gameInfo.turns
                    unit.useMovementPoints(unit.currentMovement)
                    unit.civ.addNotification("Your [Natural Philosopher] has constructed a [${engine.name}]!",
                        NotificationCategory.Units, engine.name)
                }.takeIf { unit.hasMovement() }
            ))
        }
    }

    // endregion

    // region Great Physician (medicine great person)

    /** Great Physician: instantly heals ALL damaged friendly units empire-wide, consuming this unit. */
    internal fun getGreatPhysicianActions(unit: MapUnit, tile: Tile) = sequence {
        if (unit.baseUnit.name != "Great Physician") return@sequence
        val anyDamaged = unit.civ.units.getCivUnits().any { it.health < 100 }
        yield(UnitAction(
            UnitActionType.GreatPhysicianHeal, 78f,
            action = {
                for (other in unit.civ.units.getCivUnits().toList()) {
                    if (other.health < 100) other.healBy(100)
                }
                unit.civ.addNotification("Your [Great Physician] has healed all wounded units of the empire!",
                    NotificationCategory.General, NotificationIcon.Science)
                unit.consume()
            }.takeIf { unit.hasMovement() && anyDamaged }
        ))
    }

    // endregion
}
