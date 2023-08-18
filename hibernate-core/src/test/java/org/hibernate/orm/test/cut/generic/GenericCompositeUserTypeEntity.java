package org.hibernate.orm.test.cut.generic;

import org.hibernate.annotations.CompositeType;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;

@Entity
public class GenericCompositeUserTypeEntity {
	@Lob
	@Embedded
	@CompositeType(value = EnumPlaceholderUserType.class)
	@AttributeOverrides({
			@AttributeOverride(name = "type", column = @Column(name = "TYPE", updatable = false)),
			@AttributeOverride(name = "jsonValue", column = @Column(name = "DATA", updatable = false, columnDefinition = "clob"))
	})
	protected EnumPlaceholder placeholder;
	@Id
	private final Long id;

	public GenericCompositeUserTypeEntity(EnumPlaceholder placeholder) {
		this.id = System.currentTimeMillis();
		this.placeholder = placeholder;
	}
}