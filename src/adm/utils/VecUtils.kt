package com.adm.utils

import com.adm.features.impl.dungeon.map.tile.RoomRotation
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3

operator fun Vec3.component1(): Double = x
operator fun Vec3.component2(): Double = y
operator fun Vec3.component3(): Double = z

operator fun BlockPos.component1(): Int = x
operator fun BlockPos.component2(): Int = y
operator fun BlockPos.component3(): Int = z

operator fun Vec3.unaryMinus(): Vec3 = Vec3(-x, -y, -z)

fun Vec3.addVec(x: Number = 0.0, y: Number = 0.0, z: Number = 0.0): Vec3 =
    Vec3(this.x + x.toDouble(), this.y + y.toDouble(), this.z + z.toDouble())

/**
 * Rotates a BlockPos around the given rotation.
 * @param rotation The rotation to rotate around
 * @return The rotated BlockPos
 */
fun BlockPos.rotateAroundNorth(rotation: RoomRotation): BlockPos =
    when (rotation) {
        RoomRotation.NORTH -> BlockPos(-this.x, this.y, -this.z)
        RoomRotation.WEST ->  BlockPos(-this.z, this.y, this.x)
        RoomRotation.SOUTH -> BlockPos(this.x, this.y, this.z)
        RoomRotation.EAST ->  BlockPos(this.z, this.y, -this.x)
    }

fun Vec3.rotateAroundNorth(rotation: RoomRotation): Vec3 =
    when (rotation) {
        RoomRotation.NORTH -> Vec3(-this.x, this.y, -this.z)
        RoomRotation.WEST ->  Vec3(-this.z, this.y, this.x)
        RoomRotation.SOUTH -> Vec3(this.x, this.y, this.z)
        RoomRotation.EAST ->  Vec3(this.z, this.y, -this.x)
    }

/**
 * Rotates a BlockPos to the given rotation.
 * @param rotation The rotation to rotate to
 * @return The rotated BlockPos
 */
fun BlockPos.rotateToNorth(rotation: RoomRotation): BlockPos =
    when (rotation) {
        RoomRotation.NORTH -> BlockPos(-this.x, this.y, -this.z)
        RoomRotation.WEST ->  BlockPos(this.z, this.y, -this.x)
        RoomRotation.SOUTH -> BlockPos(this.x, this.y, this.z)
        RoomRotation.EAST ->  BlockPos(-this.z, this.y, this.x)
    }

fun Vec3.rotateToNorth(rotation: RoomRotation): Vec3 =
    when (rotation) {
        RoomRotation.NORTH -> Vec3(-this.x, this.y, -this.z)
        RoomRotation.WEST ->  Vec3(this.z, this.y, -this.x)
        RoomRotation.SOUTH -> Vec3(this.x, this.y, this.z)
        RoomRotation.EAST ->  Vec3(-this.z, this.y, this.x)
    }

val BlockPos.center: Vec3 get() = Vec3.atCenterOf(this)