package com.drajer.sof.launch;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.time.DateUtils;
import org.hl7.fhir.r4.model.Period;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.drajer.eca.model.EventTypes.WorkflowEvent;
import com.drajer.ecrapp.service.WorkflowService;
import com.drajer.sof.model.ClientDetails;
import com.drajer.sof.model.LaunchDetails;
import com.drajer.sof.model.SystemLuanch;
import com.drajer.sof.service.ClientDetailsService;
import com.drajer.sof.service.LaunchService;
import com.drajer.sof.service.LoadingQueryService;
import com.drajer.sof.service.TriggerQueryService;
import com.drajer.sof.utils.Authorization;
import com.drajer.sof.utils.FhirContextInitializer;
import com.drajer.sof.utils.RefreshTokenScheduler;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.FhirVersionEnum;
import ca.uhn.fhir.model.dstu2.composite.PeriodDt;
import ca.uhn.fhir.model.dstu2.resource.Encounter;
import ca.uhn.fhir.rest.client.api.IGenericClient;

@RestController
public class LaunchController {

	private final Logger logger = LoggerFactory.getLogger(LaunchController.class);

	@Autowired
	LaunchService authDetailsService;

	@Autowired
	RefreshTokenScheduler tokenScheduler;

	@Autowired
	Authorization authorization;

	@Autowired
	TriggerQueryService triggerQueryService;

	@Autowired
	LoadingQueryService loadingQueryService;

	@Autowired
	WorkflowService workflowService;

	@Autowired
	ClientDetailsService clientDetailsService;

	@Autowired
	FhirContextInitializer fhirContextInitializer;

	@CrossOrigin
	@RequestMapping("/api/launchDetails/{tokenId}")
	public LaunchDetails getLaunchDetailsById(@PathVariable("tokenId") Integer tokenId) {
		return authDetailsService.getAuthDetailsById(tokenId);
	}

	// POST method to create a Client
	@CrossOrigin
	@RequestMapping(value = "/api/launchDetails", method = RequestMethod.POST)
	public LaunchDetails saveLaunchDetails(@RequestBody LaunchDetails launchDetails) {

		logger.info(" Saving Launch Context");
		authDetailsService.saveOrUpdate(launchDetails);

		logger.info("Scheduling refresh token job ");
		tokenScheduler.scheduleJob(launchDetails);

		// Kick off the Launch Event Processing
		logger.info("Invoking SOF Launch workflow event handler ");
		workflowService.handleWorkflowEvent(WorkflowEvent.SOF_LAUNCH, launchDetails);

		return launchDetails;
	}

	@CrossOrigin
	@RequestMapping("/api/triggerQueryService/{tokenId}")
	public String triggerDataFromEHR(@PathVariable("tokenId") Integer tokenId) {
		LaunchDetails launchDetails = authDetailsService.getAuthDetailsById(tokenId);
		SimpleDateFormat ft = new SimpleDateFormat("yyyy-MM-dd");
		try {
			Date start = ft.parse("2012-02-19");
			triggerQueryService.getData(launchDetails, start, new Date());
		} catch (ParseException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		return "Success";
	}

	@CrossOrigin
	@RequestMapping("/api/loadingQueryService/{tokenId}")
	public String loadingDataFromEHR(@PathVariable("tokenId") Integer tokenId) {
		LaunchDetails launchDetails = authDetailsService.getAuthDetailsById(tokenId);
		SimpleDateFormat ft = new SimpleDateFormat("yyyy-MM-dd");
		try {
			Date start = ft.parse("2012-02-19");
			loadingQueryService.getData(launchDetails, start, new Date());
		} catch (ParseException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		return "Success";
	}

	@CrossOrigin
	@RequestMapping(value = "/api/systemLaunch", method = RequestMethod.POST)
	public String invokeSystemLaunch(@RequestBody SystemLuanch systemLaunch) {
		try {
			ClientDetails clientDetails = clientDetailsService.getClientDetailsByUrl(systemLaunch.getFhirServerURL());
			tokenScheduler.getSystemAccessToken(clientDetails);
		} catch (Exception e) {
			logger.info("Error in Invoking System Launch");
		}

		return "App is launched successfully";
	}

	@CrossOrigin
	@RequestMapping(value = "/api/launch")
	public void launchApp(@RequestParam String launch, @RequestParam String iss, HttpServletRequest request,
			HttpServletResponse response) throws Exception {
		if (launch != null && iss != null) {
			logger.info("Received Launch Paramter:::::" + launch);
			logger.info("Received FHIR Server Base URL:::::" + iss);
			String uri = request.getScheme() + "://" + request.getServerName()
					+ ("http".equals(request.getScheme()) && request.getServerPort() == 80
							|| "https".equals(request.getScheme()) && request.getServerPort() == 443 ? ""
									: ":" + request.getServerPort())
					+ request.getContextPath();
			Random random = new Random();
			Integer state = random.nextInt();
			logger.info("Random State Value==========>"+state);
			LaunchDetails launchDetails = new LaunchDetails();
			launchDetails.setRedirectURI(uri + "/api/redirect");
			launchDetails.setEhrServerURL(iss);
			launchDetails.setLaunchId(launch);
			try {
				JSONObject object = authorization.getMetadata(iss + "/metadata");
				if (object != null) {
					logger.info("Reading Metadata information");
					JSONObject security = (JSONObject) object.getJSONArray("rest").get(0);
					JSONObject sec = security.getJSONObject("security");
					JSONObject extension = (JSONObject) sec.getJSONArray("extension").get(0);
					JSONArray innerExtension = extension.getJSONArray("extension");
					if (object.getString("fhirVersion").equals("1.0.2")) {
						launchDetails.setFhirVersion(FhirVersionEnum.DSTU2.toString());
					}
					if (object.getString("fhirVersion").equals("4.0.0")) {
						launchDetails.setFhirVersion(FhirVersionEnum.R4.toString());
					}
					for (int i = 0; i < innerExtension.length(); i++) {
						JSONObject urlExtension = innerExtension.getJSONObject(i);
						if (urlExtension.getString("url").equals("authorize")) {
							logger.info("Authorize URL:::::" + urlExtension.getString("valueUri"));
							launchDetails.setAuthUrl(urlExtension.getString("valueUri"));
						}
						if (urlExtension.getString("url").equals("token")) {
							logger.info("Token URL:::::" + urlExtension.getString("valueUri"));
							launchDetails.setTokenUrl(urlExtension.getString("valueUri"));
						}
					}
					ClientDetails clientDetails = clientDetailsService
							.getClientDetailsByUrl(launchDetails.getEhrServerURL());
					launchDetails.setClientId(clientDetails.getClientId());
					launchDetails.setScope(clientDetails.getScopes());
					launchDetails.setLaunchState(state);
					String constructedAuthUrl = authorization.createAuthUrl(launchDetails, clientDetails, state);
					logger.info("Constructed Authorization URL:::::" + constructedAuthUrl);
					authDetailsService.saveOrUpdate(launchDetails);
					// response.sendRedirect(constructedAuthUrl);
					response.setStatus(HttpServletResponse.SC_TEMPORARY_REDIRECT);
					response.setHeader("Location", constructedAuthUrl);
				}
			} catch (Exception e) {
				logger.error("Error in getting Authorization with Server");
			}
		} else {
			throw new Exception("Launch or Issuer URL is missing");
		}
	}

	@CrossOrigin
	@RequestMapping(value = "/api/redirect")
	public void redirectEndPoint(@RequestParam String code, @RequestParam String state, HttpServletRequest request,
			HttpServletResponse response) throws Exception {
		if (code != null && state != null) {
			logger.info("Received Code Parameter:::::" + code);
			logger.info("Received State Parameter:::::" + state);
			logger.info("Reading the oAuth Details stored in HashMap using state value");
			LaunchDetails currentLaunchDetails = authDetailsService.getLaunchDetailsByState(Integer.parseInt(state));
			boolean isPatientLaunched = false;
			if (currentLaunchDetails != null) {
				currentLaunchDetails.setAuthorizationCode(code);
				JSONObject accessTokenObject = authorization.getAccessToken(currentLaunchDetails);
				if (accessTokenObject != null) {
					logger.info("Received Access Token:::::" + accessTokenObject.getString("access_token"));
					if (accessTokenObject.get("patient") != null && accessTokenObject.get("encounter") != null) {
						isPatientLaunched = checkWithExistingPatientAndEncounter(accessTokenObject.getString("patient"),
								accessTokenObject.getString("encounter"), currentLaunchDetails.getEhrServerURL());
					}
					if (!isPatientLaunched) {
						ClientDetails clientDetails = clientDetailsService
								.getClientDetailsByUrl(currentLaunchDetails.getEhrServerURL());

						currentLaunchDetails = setLaunchDetails(currentLaunchDetails, accessTokenObject,
								clientDetails);

						saveLaunchDetails(currentLaunchDetails);
					} else {
						logger.error("Launch Context is already present for Patient:::::"
								+ accessTokenObject.getString("patient"));
						response.sendError(HttpServletResponse.SC_BAD_REQUEST,
								"Launch Context is already present for Patient:::::"
										+ accessTokenObject.getString("patient"));
					}
				} else {
					throw new Exception("Error in getting AccessToken from Token Endpoint");
				}
			} else {
				throw new Exception(
						"Error in getting the oAuth Details from HashMap using State Parameter:::::" + state);
			}
		} else {
			throw new Exception("Code or State Parmater is Missing");
		}

	}

	private Boolean checkWithExistingPatientAndEncounter(String patient, String encounter, String fhirServerUrl) {
		LaunchDetails launchDetails = authDetailsService.getLaunchDetailsByPatientAndEncounter(patient, encounter,
				fhirServerUrl);
		if (launchDetails != null) {
			logger.info("Launch context found with Patient::::" + patient + ", Encounter:::::" + encounter
					+ ", From EHR:::::" + fhirServerUrl);
			return true;
		} else {
			logger.info("Launch context not found");
			return false;
		}

	}

	public LaunchDetails setLaunchDetails(LaunchDetails currentStateDetails, JSONObject accessTokenObject,
			ClientDetails clientDetails) {

		currentStateDetails.setAccessToken(accessTokenObject.getString("access_token"));
		currentStateDetails.setRefreshToken(accessTokenObject.getString("refresh_token"));
		currentStateDetails.setUserId(accessTokenObject.getString("user"));
		currentStateDetails.setExpiry(accessTokenObject.getInt("expires_in"));
		currentStateDetails.setLaunchPatientId(
				accessTokenObject.getString("patient") != null ? accessTokenObject.getString("patient") : null);
		currentStateDetails.setEncounterId(
				accessTokenObject.getString("encounter") != null ? accessTokenObject.getString("encounter") : null);
		currentStateDetails.setAssigningAuthorityId(clientDetails.getAssigningAuthorityId());
		currentStateDetails.setSetId(accessTokenObject.getString("patient") + "+" + accessTokenObject.getString("encounter"));
		currentStateDetails.setVersionNumber("1");
		currentStateDetails.setDirectUser(clientDetails.getDirectUser());
		currentStateDetails.setDirectHost(clientDetails.getDirectHost());
		currentStateDetails.setDirectPwd(clientDetails.getDirectPwd());
		currentStateDetails.setDirectRecipient(clientDetails.getDirectRecipientAddress());
		currentStateDetails.setIsCovid(clientDetails.getIsCovid());

		setStartAndEndDates(clientDetails, currentStateDetails);

		return currentStateDetails;
	}

	public void setStartAndEndDates(ClientDetails clientDetails,
			LaunchDetails currentStateDetails) {
		FhirContext context = fhirContextInitializer.getFhirContext(currentStateDetails.getFhirVersion());

		IGenericClient client = fhirContextInitializer.createClient(context, currentStateDetails.getEhrServerURL(),
				currentStateDetails.getAccessToken());

		if (currentStateDetails.getFhirVersion().equals(FhirVersionEnum.DSTU2.toString())
				&& currentStateDetails.getEncounterId() != null) {
			Encounter encounter = (Encounter) fhirContextInitializer.getResouceById(currentStateDetails, client, context,
					"Encounter", currentStateDetails.getEncounterId());
			if (encounter.getPeriod() != null) {
				PeriodDt period = encounter.getPeriod();
				if (period.getStart() != null) {
					currentStateDetails.setStartDate(period.getStart());
				} else {
					Date startDate = DateUtils.addHours(new Date(),
							Integer.parseInt(clientDetails.getEncounterStartThreshold()));
					currentStateDetails.setStartDate(startDate);
				}
				if (period.getEnd() != null) {
					currentStateDetails.setEndDate(period.getEnd());
				} else {
					Date endDate = DateUtils.addHours(new Date(),
							Integer.parseInt(clientDetails.getEncounterEndThreshold()));
					currentStateDetails.setEndDate(endDate);
				}
			}
		}

		if (currentStateDetails.getFhirVersion().equals(FhirVersionEnum.R4.toString())
				&& currentStateDetails.getEncounterId() != null) {
			org.hl7.fhir.r4.model.Encounter r4Encounter = (org.hl7.fhir.r4.model.Encounter) fhirContextInitializer
					.getResouceById(currentStateDetails, client, context, "Encounter", currentStateDetails.getEncounterId());
			if (r4Encounter != null) {
				if (r4Encounter.getPeriod() != null) {
					Period period = r4Encounter.getPeriod();
					if (period.getStart() != null) {
						currentStateDetails.setStartDate(period.getStart());
					} else {
						Date startDate = DateUtils.addHours(new Date(),
								Integer.parseInt(clientDetails.getEncounterStartThreshold()));
						currentStateDetails.setStartDate(startDate);
					}
					if (period.getEnd() != null) {
						currentStateDetails.setEndDate(period.getEnd());
					} else {
						Date endDate = DateUtils.addHours(new Date(),
								Integer.parseInt(clientDetails.getEncounterEndThreshold()));
						currentStateDetails.setEndDate(endDate);
					}
				}
			}
		}
	}
}
