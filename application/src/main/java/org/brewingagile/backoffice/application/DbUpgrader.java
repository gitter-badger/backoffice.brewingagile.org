package org.brewingagile.backoffice.application;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import com.hencjo.summer.migration.Migrator;
import com.hencjo.summer.migration.dsl.*;
import static com.hencjo.summer.migration.dsl.DSL.*;

public class DbUpgrader {
	public static MigrationsDescription piUpgradeDescription() {
		return migrations(
			migration("registrations-table").installsThrough(script("001-registrations.sql")),
			migration("dietary-requirements-and-workshop").installsThrough(script("002-dietary-requirements-and-workshop.sql")),
			migration("dietary-requirements-and-workshop-as-string").installsThrough(script("003-dietary-requirements-and-workshop-as-string.sql")),
			migration("fixed-registration-date-and-added-primary-key.sql").installsThrough(script("004-fixed-registration-date-and-added-primary-key.sql")), 
			migration("add-billing-method").installsThrough(script("005-add-billing-method.sql")),
			migration("convert-id-to-uuid").installsThrough(script("006-convert-id-to-uuid.sql")),
			migration("invoice-registrations").installsThrough(script("007-invoice-registrations.sql")),
			migration("registration-states").installsThrough(script("008-registration-states.sql")),
			migration("participant-roles").installsThrough(script("009-participant-roles.sql")),
			migration("010-organizer-to-organiser").installsThrough(script("010-organizer-to-organiser.sql")),
			migration("twitter-handle").installsThrough(script("011-twitter-handle.sql")),
			migration("participant-buckets").installsThrough(script("012-participant-buckets.sql")),
			migration("deferrable-buckets").installsThrough(script("013-deferrable-buckets.sql")),
			migration("role-to-badge").installsThrough(script("014-role-to-badge.sql")),
			migration("printed-name-tags").installsThrough(script("015-printed-name-tags.sql")),
			migration("rename-id-to-registration-id").installsThrough(script("016-rename-id-to-registration-id.sql"))
		);
	}

	public void upgrade(DataSource ds) throws SQLException, IOException {
		Migrator migrator = new Migrator();
		try (Connection connection = ds.getConnection()) {
			migrator.migrate(connection, piUpgradeDescription());
		}
	}
}
