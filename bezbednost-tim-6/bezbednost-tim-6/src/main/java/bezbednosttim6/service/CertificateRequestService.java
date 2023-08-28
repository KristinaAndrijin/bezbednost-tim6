package bezbednosttim6.service;

import bezbednosttim6.controller.CertificateController;
import bezbednosttim6.dto.CertificateRequestDTO;
import bezbednosttim6.dto.CertificateRequestResponseDTO;
import bezbednosttim6.dto.DTOList;
import bezbednosttim6.exception.*;
import bezbednosttim6.model.*;
import bezbednosttim6.repository.CertificateRepository;
import bezbednosttim6.repository.CertificateRequestRepository;
import bezbednosttim6.repository.UserRepository;
import bezbednosttim6.security.LogIdUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.Principal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class CertificateRequestService {
    @Autowired
    private CertificateRequestRepository certificateRequestRepo;
    @Autowired
    private UserRepository userRepo;

    @Autowired
    private CertificateService certificateService;

    @Autowired
    private CertificateRepository certificateRepo;
    private static final Logger logger = LogManager.getLogger(CertificateRequestService.class);
    private LogIdUtil util = new LogIdUtil();

    public CertificateRequestResponseDTO createRequest(CertificateRequestDTO certificateRequestDTO, Principal principal) throws Exception {

        // nece se desiti...
        User user = userRepo.findUserByEmail(principal.getName());
        if (user == null)
        {
            throw new UserNotFoundException("User not found.");
        }
        util.getNewLogId();
        logger.info("User with id: " + user.getId().toString() + " has requested a certificate");

        String requestedTypeStr = certificateRequestDTO.getCertificateType().toUpperCase().trim();
        CertificateType requestedType = findCertificateType(requestedTypeStr);

        // resavamo root
        if (requestedType == CertificateType.ROOT && certificateRequestDTO.getIssuerCertificateId() != null) {
//            util.getNewLogId();
//            logger.error("Root certificate is self-signed and issuer field should be None");
            throw new TypePermissionException("Root certificate is self-signed and issuer field should be None");
        }

        if (requestedType == CertificateType.ROOT && user.getRole().getName().equals("ROLE_USER")) {
//            util.getNewLogId();
//            logger.error("You don't have the permission to create root certificates");
            throw new TypePermissionException("You don't have the permission to create root certificates");
        }

        Optional<Certificate> certificateOpt = certificateRepo.findBySerialNumber(certificateRequestDTO.getIssuerCertificateId());
        Certificate fromCertificate;

        RequestStatus requestStatus = RequestStatus.CREATED;
        CertificateRequest newRequest;

        LocalDateTime now = LocalDateTime.now();
        Duration duration;
        try {
            // pogledajte (hoverujte) .parse metodu za primere
            duration = Duration.parse(certificateRequestDTO.getDuration());
        } catch (DateTimeParseException e) {
//            util.getNewLogId();
//            logger.error("Error parsing duration: " + e.getMessage());
            throw new IllegalArgumentException("Invalid duration provided");
        }

        if (certificateOpt.isEmpty())
        {
            if (requestedType!=CertificateType.ROOT)
            {
                throw new CertificateNotFoundException("Issuer certificate not found");
            } 
            else {
//                fromCertificate = certificateOpt.get();
                newRequest = new CertificateRequest(requestedType, certificateRequestDTO.getIssuerCertificateId(), null, user.getId(), user.getEmail(), requestStatus, now, duration, certificateRequestDTO.getCommonName(), null);
                newRequest = certificateRequestRepo.save(newRequest);
                // automatsko odobravanje
                // admin trazi novi sertifikat
                if (user.getRole().getId()==1) {
                    newRequest = accept(user, newRequest);
                }
            }
        } else {
            fromCertificate = certificateOpt.get();
            if (fromCertificate.certificateType == CertificateType.END) {
                throw new TypePermissionException("Cannot make certificate from an end certificate");
            }
            if (!checkIfValidDuration(fromCertificate,duration))
            {
                throw new InvalidArgumentException("Requested duration is longer than possible");
            }
            newRequest = new CertificateRequest(requestedType, certificateRequestDTO.getIssuerCertificateId(), fromCertificate.getUser().getId(), user.getId(), user.getEmail(), requestStatus, now, duration, certificateRequestDTO.getCommonName(), null);
            newRequest = certificateRequestRepo.save(newRequest);

            // automatsko odobravanje
            // vlasnik roditeljskog sertifikata trazi dete
            // admin trazi novi sertifikat
            if (user.getRole().getId()==1 || Objects.equals(user.getId(), fromCertificate.getUser().getId())) {
                newRequest = accept(user, newRequest);
            }
        }

//        newRequest = new CertificateRequest(requestedType, certificateRequestDTO.getIssuerCertificateId(), fromCertificate.getUser().getId(), user.getId(), requestStatus, now, duration, certificateRequestDTO.getCommonName(), null);

        util.getNewLogId();
        logger.info("Certificate successfully requested");
        return new CertificateRequestResponseDTO(newRequest.getId(), certificateRequestDTO.getCertificateType(), certificateRequestDTO.getIssuerCertificateId(),user.getId(), user.getEmail(), now, newRequest.getCommonName(), newRequest.getStatus().toString(), null);

    }


    public DTOList<CertificateRequestResponseDTO> getAllForUser(String mail) {

        User user = userRepo.findUserByEmail(mail);
        if (user == null)
        {
            throw new UserNotFoundException("User not found, requested to view requests.");
        }
        util.getNewLogId();
        logger.info("User with id: " + user.getId().toString() + " wants to view sent requests");
        List<CertificateRequest> requests = certificateRequestRepo.findAllByUserId(user.getId());
        util.getNewLogId();
        logger.info("List returned successfully");
        return getCertificateRequestResponseDTOList(requests);
    }

    public DTOList<CertificateRequestResponseDTO> getAllForIssuer(String mail) {

        User user = userRepo.findUserByEmail(mail);
        if (user == null)
        {
            throw new UserNotFoundException("User not found, requested to view requests.");
        }
        util.getNewLogId();
        logger.info("User with id: " + user.getId().toString() + " wants to view received requests");
        List<CertificateRequest> requests = certificateRequestRepo.findAllByIssuerIdAndStatus(user.getId(), RequestStatus.CREATED);
        util.getNewLogId();
        logger.info("List returned successfully");
        return getCertificateRequestResponseDTOList(requests);
    }

    private DTOList<CertificateRequestResponseDTO> getCertificateRequestResponseDTOList(List<CertificateRequest> requests) {
        DTOList<CertificateRequestResponseDTO> dtoList = new DTOList<>();
        for (CertificateRequest cr : requests)
        {
            CertificateRequestResponseDTO dto = new CertificateRequestResponseDTO(cr);
            dtoList.add(dto);
        }
        return dtoList;
    }

    public DTOList<CertificateRequestResponseDTO> getAll() {
        List<CertificateRequest> requests = certificateRequestRepo.findAll();
        return getCertificateRequestResponseDTOList(requests);
    }

    public CertificateRequest rejectRequest(Long requestId, String reasonDTO, Principal principal) {
        User user = userRepo.findUserByEmail(principal.getName());
        util.getNewLogId();
        logger.warn("User with id: " + user.getId().toString() + " wants to reject request: " + requestId.toString());
        Long userId = user.getId();
        Optional<CertificateRequest> requestOpt = certificateRequestRepo.findById(requestId);
        if (requestOpt.isEmpty()) throw new CertificateRequestNotFoundException("Certificate request not found");
        CertificateRequest request = requestOpt.get();
        if (request.getStatus()!=RequestStatus.CREATED) throw new CertificateRequestNotFoundException("Request is already processed");
        Long reqOwnerId = request.getIssuerId();
        if (!Objects.equals(reqOwnerId, userId)) throw new CertificateRequestNotFoundException("Cannot access this request");
        request.setRejection(reasonDTO);
        request.setStatus(RequestStatus.REJECTED);
        request = certificateRequestRepo.save(request);
        util.getNewLogId();
        logger.info("Request with id: " + requestId.toString() +"successfully rejected");
        return request;
    }

    public CertificateRequest acceptRequest(Long requestId, Principal principal) throws Exception {
        User user = userRepo.findUserByEmail(principal.getName());
        util.getNewLogId();
        logger.warn("User with id: " + user.getId().toString() + " wants to accept request: " + requestId.toString());
        Long userId = user.getId();
        Optional<CertificateRequest> requestOpt = certificateRequestRepo.findById(requestId);
        if (requestOpt.isEmpty()) throw new CertificateRequestNotFoundException("Certificate request not found");
        CertificateRequest request = requestOpt.get();
        if (request.getStatus()!=RequestStatus.CREATED) throw new CertificateRequestNotFoundException("Request is already processed");
        Long reqOwnerId = request.getIssuerId();
        if (!Objects.equals(reqOwnerId, userId)) throw new CertificateRequestNotFoundException("Cannot access this request");
        request = accept(user, request);

        return request;
    }

    private CertificateRequest accept(User user2, CertificateRequest request) throws Exception {
        request.setStatus(RequestStatus.ACCEPTED);
        request = certificateRequestRepo.save(request);
        Optional<User> opp = userRepo.findUserById(request.getUserId());
        User user = user2;
        if (opp.isPresent()) {
            user = opp.get();
        }
        util.getNewLogId();
        logger.info("Request with id: " + request.getId().toString() + " successfully accepted");

        CertificateType type = request.getCertificateType();

        util.getNewLogId();
        logger.warn("Creating certificate after accepting request if possible");
        switch (type) {
            case ROOT -> certificateService.createRoot(user, request.getCommonName(), request.getDuration());
            case INTERMEDIATE -> certificateService.createIntermediate(user, request.getIssuerCertificateId(), request.getCommonName(), request.getDuration());
            case END -> certificateService.createEnd(user, request.getIssuerCertificateId(), request.getCommonName(), request.getDuration());
            default -> throw new Exception("???");
        }
        return request;
    }

    private boolean checkIfValidDuration(Certificate fromCertificate, Duration requestedDuration) {
        //TODO: zapravo dobaviti certifikat, napraviti duration od now i enddate

        //ovako?
        long milis = requestedDuration.toMillis();
        long now = new Date().getTime(); //		Date now = new Date();
        long possibleEndTime = now + milis;
        Date endTime = new Date(possibleEndTime);
        Date endIssuer = fromCertificate.getValidTo();
        return endTime.before(endIssuer);


        //"something must be done, 500 years" xdd
//		Duration dummyDuration = Duration.of(500L * 365, ChronoUnit.DAYS);
//
//
//		if (requestedDuration.compareTo(dummyDuration) > 0) {
//			return false;
//		}
//		return true;
    }


    private boolean checkIfCertificateExist(Long issuerCertificateId) {
        Optional<Certificate> certificateOpt = certificateRepo.findBySerialNumber(issuerCertificateId);
        return certificateOpt.isPresent();
    }

    private CertificateType findCertificateType(String type)
    {
        if (type.equals("ROOT"))
            return CertificateType.ROOT;
        else if (type.equals("INTERMEDIATE"))
            return CertificateType.INTERMEDIATE;
        else if (type.equals("END"))
            return CertificateType.END;
        else throw new TypeNotFoundException("Type of certificate is not valid.");
    }
}
