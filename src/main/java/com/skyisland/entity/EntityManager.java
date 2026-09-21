package com.skyisland.entity;

import com.skyisland.player.Player;
import com.skyisland.util.Coords;
import com.skyisland.util.Log;
import com.skyisland.world.World;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 实体管理器（M2）。
 *
 * <p><b>为什么用"每帧正向遍历 + removeIf 清理"而不是边遍历边删：</b>
 * 实体在自己的 {@code tick} 里可能改变存活状态（被打死、掉进虚空），
 * 若在遍历中立刻删除，同一帧后续实体的 tick 就会看到一个"少了一个"的列表 ——
 * 更糟的是 {@link com.skyisland.combat.Hitscan} 在同一帧里已经拿到的实体引用会变成悬空语义。
 * 因此规则是：<b>本帧先全部 tick，帧末统一清理</b>。
 * 一个已死实体在死亡当帧仍然参与命中判定是<u>无害</u>的（它已经不掉血了），
 * 而列表在遍历中被改动则是难以复现的崩溃来源。
 */
public final class EntityManager {

    private final List<Entity> entities = new ArrayList<>();

    private int totalSpawned = 0;
    private int totalRemoved = 0;

    /** 登记一个实体。 */
    public void add(Entity entity) {
        if (entity != null) {
            entities.add(entity);
            totalSpawned++;
        }
    }

    /** 调试指令入口：在指定位置生成一只近战怪（PRD 11 章 M2 补充口径）。 */
    public MeleeMonster spawnMeleeMonster(double x, double y, double z) {
        MeleeMonster monster = new MeleeMonster(x, y, z);
        add(monster);
        Log.info("[实体] 生成近战怪于 (%.2f, %.2f, %.2f)", x, y, z);
        return monster;
    }

    /** 全部实体（只读视图，含已死亡但尚未清理的）。 */
    public List<Entity> all() {
        return Collections.unmodifiableList(entities);
    }

    /** 当前存活数量。 */
    public int aliveCount() {
        int n = 0;
        for (Entity e : entities) {
            if (e.isAlive()) {
                n++;
            }
        }
        return n;
    }

    public int size() {
        return entities.size();
    }

    public int totalSpawned() {
        return totalSpawned;
    }

    public int totalRemoved() {
        return totalRemoved;
    }

    /** 推进所有实体一帧，并在帧末清理死亡 / 坠入虚空的实体。 */
    public void tick(World world, Player player, double dt) {
        for (int i = 0; i < entities.size(); i++) {
            Entity e = entities.get(i);
            if (e.isAlive()) {
                e.tick(world, player, dt);
            }
        }
        entities.removeIf(e -> {
            boolean gone = !e.isAlive() || Coords.isVoidDeath(e.position().y);
            if (gone) {
                totalRemoved++;
            }
            return gone;
        });
    }

    /** 距离给定点最近的存活实体；超出 {@code maxDistance} 返回 {@code null}。 */
    public Entity nearestTo(Vector3d point, double maxDistance) {
        Entity best = null;
        double bestSq = maxDistance * maxDistance;
        for (Entity e : entities) {
            if (!e.isAlive()) {
                continue;
            }
            double dSq = e.position().distanceSquared(point);
            if (dSq <= bestSq) {
                bestSq = dSq;
                best = e;
            }
        }
        return best;
    }

    /** 清空（新世界 / 读档时用）。 */
    public void clear() {
        entities.clear();
    }
}
