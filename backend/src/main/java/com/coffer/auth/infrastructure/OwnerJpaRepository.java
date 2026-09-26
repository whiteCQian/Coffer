package com.coffer.auth.infrastructure;

import com.coffer.auth.domain.TenantOwnedEntity;
import com.coffer.auth.service.ResourceNotFoundException;
import com.coffer.auth.service.TenantContext;
import jakarta.persistence.EntityManager;
import org.springframework.data.jpa.repository.support.JpaEntityInformation;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Primary-key loads and detached writes must not bypass Hibernate's query discriminator. */
@Transactional(readOnly = true)
public class OwnerJpaRepository<T, ID> extends SimpleJpaRepository<T, ID> {
    private final JpaEntityInformation<T, ?> information;
    private final EntityManager em;
    private final boolean owned;

    public OwnerJpaRepository(JpaEntityInformation<T, ?> information, EntityManager em) {
        super(information, em);
        this.information = information;
        this.em = em;
        this.owned = TenantOwnedEntity.class.isAssignableFrom(information.getJavaType());
    }

    @Override
    public Optional<T> findById(ID id) {
        if (!owned) return super.findById(id);
        var cb = em.getCriteriaBuilder();
        var query = cb.createQuery(information.getJavaType());
        var root = query.from(information.getJavaType());
        query.select(root).where(cb.equal(root.get(information.getIdAttribute().getName()), id),
                cb.equal(root.get("ownerId"), TenantContext.requireOwnerId()));
        return em.createQuery(query).getResultStream().findFirst();
    }

    @Override public boolean existsById(ID id) { return findById(id).isPresent(); }
    @Override public T getReferenceById(ID id) { return findById(id).orElseThrow(ResourceNotFoundException::new); }
    @Override public T getById(ID id) { return getReferenceById(id); }
    @Override public T getOne(ID id) { return getReferenceById(id); }

    @Override @Transactional
    public <S extends T> S save(S entity) {
        checkWrite(entity);
        return super.save(entity);
    }

    private void checkWrite(T entity) {
        if (!owned) return;
        Long owner = TenantContext.requireOwnerId();
        var row = (TenantOwnedEntity) entity;
        if (row.getOwnerId() != null && !owner.equals(row.getOwnerId())) throw new ResourceNotFoundException();
        Object id = information.getId(entity);
        // Read only for authorization; never return an unscoped entity to the caller.
        if (id != null) {
            T existing = em.find(information.getJavaType(), id);
            // Assigned-ID entities may already be managed before Hibernate generates their
            // @TenantId at insert. Flush that pending insert before comparing ownership.
            if (existing instanceof TenantOwnedEntity pending && pending.getOwnerId() == null && em.contains(existing)) {
                em.flush();
            }
            if (existing == null && information.getIdAttribute().getJavaMember() instanceof java.lang.reflect.Field field
                    && field.isAnnotationPresent(jakarta.persistence.GeneratedValue.class)) {
                throw new ResourceNotFoundException();
            }
            if (existing != null && !owner.equals(((TenantOwnedEntity) existing).getOwnerId())) {
                em.detach(existing);
                throw new ResourceNotFoundException();
            }
        }
        if (entity instanceof com.coffer.entity.ModelRuntimeSetting setting && !owner.equals(setting.getId())) {
            throw new ResourceNotFoundException();
        }
    }

    @Override @Transactional public void delete(T entity) { checkWrite(entity); super.delete(entity); }
    @Override @Transactional public void deleteById(ID id) { findById(id).ifPresent(this::delete); }
    @Override @Transactional public void deleteAllByIdInBatch(Iterable<ID> ids) { ids.forEach(this::deleteById); }
    @Override @Transactional public void deleteAllInBatch(Iterable<T> entities) { entities.forEach(this::delete); }
    @Override @Transactional public void deleteAllInBatch() { findAll().forEach(this::delete); }
}
