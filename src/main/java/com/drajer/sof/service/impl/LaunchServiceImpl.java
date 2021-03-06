package com.drajer.sof.service.impl;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.drajer.sof.dao.LaunchDetailsDao;
import com.drajer.sof.model.LaunchDetails;
import com.drajer.sof.service.LaunchService;

@Service
@Transactional
public class LaunchServiceImpl implements LaunchService{

	@Autowired
	LaunchDetailsDao authDetailsDao;
	
	public LaunchDetails saveOrUpdate(LaunchDetails authDetails) {
		authDetailsDao.saveOrUpdate(authDetails);
		return authDetails;
	}

	public LaunchDetails getAuthDetailsById(Integer id) {
		return authDetailsDao.getAuthDetailsById(id);
	}

	public LaunchDetails getLaunchDetailsByPatientAndEncounter(String patient, String encounter, String fhirServerUrl) {
		return authDetailsDao.getLaunchDetailsByPatientAndEncounter(patient, encounter, fhirServerUrl);
	}

	@Override
	public List<LaunchDetails> getAllLaunchDetails() {
		return authDetailsDao.getAllLaunchDetails();
	
        public LaunchDetails getLaunchDetailsByState(int state) {
		return authDetailsDao.getLaunchDetailsByState(state);
	}

}
