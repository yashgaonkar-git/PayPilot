package dev.paypilot;

import static dev.paypilot.Models.*;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
class Store {
    private final EntityManager em;

    Store(EntityManager em) {
        this.em = em;
    }

    void save(Object entity) {
        em.persist(entity);
    }

    void remove(Object entity) {
        em.remove(entity);
    }

    <T> T find(Class<T> entity, Object id) {
        return em.find(entity, id);
    }

    <T> T lock(Class<T> entity, Object id) {
        return em.find(entity, id, LockModeType.PESSIMISTIC_WRITE);
    }

    <T> List<T> findAll(Class<T> type, String hql, Object... parameters) {
        var query = em.createQuery(hql, type);
        for (int i = 0; i < parameters.length; i++) query.setParameter(i + 1, parameters[i]);
        return query.getResultList();
    }

    <T> T first(Class<T> type, String hql, Object... parameters) {
        var query = em.createQuery(hql, type);
        for (int i = 0; i < parameters.length; i++) query.setParameter(i + 1, parameters[i]);
        var rows = query.setMaxResults(1).getResultList();
        return rows.isEmpty() ? null : rows.get(0);
    }

    void delete(String hql, Object... parameters) {
        var query = em.createQuery(hql);
        for (int i = 0; i < parameters.length; i++) query.setParameter(i + 1, parameters[i]);
        query.executeUpdate();
    }

    void audit(Account actor, String action, Object target) {
        AuditEntry entry = new AuditEntry();
        entry.actor = actor;
        entry.action = action;
        entry.target = target.toString();
        entry.recordedAt = Instant.now();
        save(entry);
    }
}
