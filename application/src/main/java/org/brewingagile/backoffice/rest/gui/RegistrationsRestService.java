package org.brewingagile.backoffice.rest.gui;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import javax.servlet.http.HttpServletRequest;
import javax.sql.DataSource;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import static argo.jdom.JsonNodeFactories.*;

import argo.jdom.JsonNode;
import argo.jdom.JsonRootNode;
import argo.saj.InvalidSyntaxException;
import fj.*;
import fj.data.Collectors;
import fj.data.Either;
import fj.data.List;
import fj.data.Option;
import fj.function.Strings;
import org.brewingagile.backoffice.application.Application;
import org.brewingagile.backoffice.auth.AuthService;
import org.brewingagile.backoffice.db.operations.RegistrationState;
import org.brewingagile.backoffice.db.operations.RegistrationsSqlMapper;
import org.brewingagile.backoffice.db.operations.RegistrationsSqlMapper.Badge;
import org.brewingagile.backoffice.services.DismissRegistrationService;
import org.brewingagile.backoffice.services.MarkAsCompleteService;
import org.brewingagile.backoffice.services.MarkAsPaidService;
import org.brewingagile.backoffice.services.SendInvoiceService;
import org.brewingagile.backoffice.utils.ArgoUtils;
import org.brewingagile.backoffice.utils.Result;

import org.brewingagile.backoffice.utils.jersey.NeverCache;

@Path("/registrations/")
@NeverCache
public class RegistrationsRestService {
	private final DataSource dataSource = Application.INSTANCE.dataSource();
	private final AuthService authService = Application.INSTANCE.authService();
	private final RegistrationsSqlMapper registrationsSqlMapper = Application.INSTANCE.registrationsSqlMapper();
	private final SendInvoiceService sendInvoiceService = Application.INSTANCE.sendInvoiceService();
	private final DismissRegistrationService dismissRegistrationService = Application.INSTANCE.dismissRegistrationService();
	private final MarkAsCompleteService markAsCompleteService = Application.INSTANCE.markAsCompleteService();
	private final MarkAsPaidService markAsPaidService = Application.INSTANCE.markAsPaidService();
	
	@GET
	@Produces(MediaType.APPLICATION_JSON)
	public Response invoices(@Context HttpServletRequest request) throws Exception {
		authService.guardAuthenticatedUser(request);
		
		try (Connection c = dataSource.getConnection()) {
			List<RegistrationsSqlMapper.Registration> all = registrationsSqlMapper.all(c);
			JsonRootNode overview = object(
				field("received", array(all.filter(r -> r.state == RegistrationState.RECEIVED).map(RegistrationsRestService::json))),
				field("invoicing", array(all.filter(r -> r.state == RegistrationState.INVOICING).map(RegistrationsRestService::json))),
				field("paid", array(all.filter(r -> r.state == RegistrationState.PAID).map(RegistrationsRestService::json)))
			);
			return Response.ok(ArgoUtils.format(overview)).build();
		}
	}

	@GET
	@Path("/{registrationId}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getRegistration(@Context HttpServletRequest request, @PathParam("registrationId") UUID id) {
		authService.guardAuthenticatedUser(request);
		try {
			try (Connection c = dataSource.getConnection()) {
				return registrationsSqlMapper.one(c, id)
					.map(RegistrationsRestService::json)
					.map(ArgoUtils::format)
					.map(Response::ok)
					.orSome(Response.status(Status.NOT_FOUND))
					.build();
			}
		} catch (SQLException e) {
			e.printStackTrace();
			return Response.serverError().build();
		}
	}

	public static argo.jdom.JsonRootNode json(RegistrationsSqlMapper.Registration r) {
		return object(
			field("id", string(r.id.toString())),
			field("participantName", string(r.participantName)),
			field("participantEmail", string(r.participantEmail)),
			field("billingCompany", string(r.billingCompany)),
			field("billingAddress", string(r.billingAddress)),
			field("billingMethod", string(r.billingMethod.name())),
			field("twitter", string(r.twitter)),
			field("ticket", string(r.ticket)),
			field("dietaryRequirements", string(r.dietaryRequirements)),
			field("badge", string(r.badge.badge)),
			field("bundle", string(r.bundle.orSome("")))
		);
	}
	
	public static final class RegistrationsUpdate {
		public final Badge badge;
		public final String dietaryRequirements;
		public final Option<String> bundle;

		public RegistrationsUpdate(
			Badge badge,
			String dietaryRequirements,
			Option<String> bundle
		) {
			this.badge = badge;
			this.dietaryRequirements = dietaryRequirements;
			this.bundle = bundle;
		}
	}

	private static Either<String, RegistrationsUpdate> registrationsUpdate(JsonNode jsonNode) {
		Either<String, Badge> badge = ArgoUtils.stringValue(jsonNode, "badge").right().map(Badge::new);
		Either<String, String> dietaryRequirements = ArgoUtils.stringValue(jsonNode, "dietaryRequirements");
		Either<String, Option<String>> bundle = ArgoUtils.stringValue(jsonNode, "bundle")
			.right().map(Option::fromNull)
			.right().map(r -> r.filter(Strings.isNotNullOrEmpty));

		return bundle.right()
			.apply(dietaryRequirements.right()
				.apply(badge.right()
					.apply(Either.right(Function.curry(RegistrationsUpdate::new)))));
	}

	@POST
	@Path("/{registrationId}")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response postUpdate(@Context HttpServletRequest request, @PathParam("registrationId") UUID id, String body) throws Exception {
		authService.guardAuthenticatedUser(request);
		Either<String, RegistrationsUpdate> map = ArgoUtils.parseEither(body)
			.right()
			.bind(RegistrationsRestService::registrationsUpdate);

		if (map.isLeft()) return Response.serverError().build();
		RegistrationsUpdate ru = map.right().value();
		
		try (Connection c = dataSource.getConnection()) {
			c.setAutoCommit(false);
			if (!registrationsSqlMapper.one(c, id).isSome()) return Response.status(Status.NOT_FOUND).build();
			registrationsSqlMapper.update(c, id, ru.badge, ru.dietaryRequirements, ru.bundle);
			c.commit();
		}
		return Response.ok().build();
	}

	@POST
	@Path("/send-invoices")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response postSendInvoices(@Context HttpServletRequest request, String body) throws Exception {
		authService.guardAuthenticatedUser(request);

		int invoicesSent = 0;
		for (UUID uuid : registrationListRequest(body)) {
			sendInvoiceService.sendInvoice(uuid);
			invoicesSent++;
		}

		return Response.ok(ArgoUtils.format(Result.success(String.format("Skickade %s fakturor.", invoicesSent)))).build();
	}

	@POST
	@Path("/dismiss-registrations")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response postDismissRegistrations(@Context HttpServletRequest httpRequest, String body) throws Exception {
		authService.guardAuthenticatedUser(httpRequest);

		int invoicesDismissed = 0;
		for (UUID uuid : registrationListRequest(body)) {
			dismissRegistrationService.dismissRegistration(uuid);
			invoicesDismissed++;
		}

		return Response.ok(ArgoUtils.format(Result.success(String.format("Avfärdade %s registreringar.", invoicesDismissed)))).build();
	}
	
	@POST
	@Path("/mark-as-complete")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response postMarkAsComplete(@Context HttpServletRequest httpRequest, String body) throws Exception {
		authService.guardAuthenticatedUser(httpRequest);

		int i = 0;
		for (UUID uuid : registrationListRequest(body)) {
			markAsCompleteService.markAsComplete(uuid);
			i++;
		}

		return Response.ok(ArgoUtils.format(Result.success(String.format("Flyttade %s registreringar.", i)))).build();
	}

	@POST
	@Path("/mark-as-paid")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response postMarkAsPaid(@Context HttpServletRequest httpRequest, String body) throws Exception {
		authService.guardAuthenticatedUser(httpRequest);

		int i = 0;
		for (UUID uuid : registrationListRequest(body)) {
			markAsPaidService.markAsPaid(uuid);
			i++;
		}

		return Response.ok(ArgoUtils.format(Result.success(String.format("%s registreringar markerade som betalda.", i)))).build();
	}

	private static List<UUID> registrationListRequest(String body) throws InvalidSyntaxException {
		return ArgoUtils.parse(body)
			.getArrayNode("registrations")
			.stream()
			.map(RegistrationsRestService::uuid)
			.collect(Collectors.toList());
	}

	private static UUID uuid(JsonNode jsonNode) {
		return UUID.fromString(jsonNode.getStringValue());
	}
}
