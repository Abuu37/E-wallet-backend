package com.application.e_wallet.customer.service;

import com.application.e_wallet.auth.dto.CustomerRegistrationRequest;
import com.application.e_wallet.auth.dto.RegistrationResponse;
import com.application.e_wallet.auth.repository.UserSessionRepository;
import com.application.e_wallet.common.event.UserRegisteredEvent;
import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.common.exception.DuplicationResourceException;
import com.application.e_wallet.common.validation.PhoneNumberNormalizer;
import com.application.e_wallet.customer.dto.CustomerListResponse;
import com.application.e_wallet.customer.dto.CustomerListSummary;
import com.application.e_wallet.customer.dto.CustomerProfileResponse;
import com.application.e_wallet.customer.dto.CustomerStatusUpdateRequest;
import com.application.e_wallet.customer.dto.CustomerSummaryResponse;
import com.application.e_wallet.customer.dto.CustomerTransactionItem;
import com.application.e_wallet.customer.dto.CustomerTransactionSummary;
import com.application.e_wallet.customer.dto.CustomerWalletSummary;
import com.application.e_wallet.customer.entity.CustomerEntity;
import com.application.e_wallet.customer.entity.CustomerStatus;
import com.application.e_wallet.customer.repository.CustomerRepository;
import com.application.e_wallet.role.entity.RoleEntity;
import com.application.e_wallet.role.repository.RoleRepository;
import com.application.e_wallet.transaction.entity.TransactionEntity;
import com.application.e_wallet.transaction.repository.TransactionRepository;
import com.application.e_wallet.user.entity.UserEntity;
import com.application.e_wallet.user.entity.UserStatus;
import com.application.e_wallet.user.repository.UserRepository;
import com.application.e_wallet.wallet.entity.WalletEntity;
import com.application.e_wallet.wallet.entity.WalletStatus;
import com.application.e_wallet.wallet.entity.WalletType;
import com.application.e_wallet.wallet.repository.WalletRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final UserSessionRepository userSessionRepository;
    private final PhoneNumberNormalizer phoneNumberNormalizer;
    private final ApplicationEventPublisher eventPublisher;

    //======================== REGISTER A CUSTOMER (self-registration) ==================== //
    @Transactional
    public RegistrationResponse registerCustomer(CustomerRegistrationRequest request) {

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new DuplicationResourceException("Email already exists");
        }

        String normalizedPhone = phoneNumberNormalizer.normalize(request.getPhone());

        if (userRepository.existsByPhone(normalizedPhone)) {
            throw new DuplicationResourceException("Phone already exists");
        }

        if (userRepository.existsByLoginUsername(request.getUsername().trim())) {
            throw new DuplicationResourceException("Username already exists");
        }

        RoleEntity customerRole = roleRepository.findByName("CUSTOMER")
                .orElseThrow(() -> new IllegalStateException("CUSTOMER role not found"));

        UserEntity user = UserEntity.builder()
                .loginUsername(request.getUsername().trim())
                .email(request.getEmail().trim().toLowerCase())
                .phone(normalizedPhone)
                .status(UserStatus.AWAITING_PIN)
                .role(customerRole)
                .build();

        UserEntity savedUser = userRepository.save(user); // ======== user saved in database ========

        String middleName = request.getMiddleName() != null ? request.getMiddleName().trim() : "";

        CustomerEntity customer = CustomerEntity.builder()
                .user(savedUser)
                .firstName(request.getFirstName().trim())
                .middleName(middleName)
                .lastName(request.getLastName().trim())
                .email(savedUser.getEmail())
                .phoneNumber(savedUser.getPhone())
                .nidaNumber(request.getNidaNumber().trim())
                .dateOfBirth(request.getDateOfBirth())
                .address(request.getAddress().trim())
                .status(CustomerStatus.PENDING)
                .build();

        customerRepository.save(customer); //=== Customer account created ====

        eventPublisher.publishEvent(new UserRegisteredEvent(savedUser.getId(), savedUser.getEmail()));

        return RegistrationResponse.builder()
                .userId(savedUser.getId())
                .status(savedUser.getStatus())
                .build();
    }

    // Wallet creation stays out of registration: the CUSTOMER_MAIN wallet is created later,
    // in AuthService.setPin(), once the customer has verified their email and set a PIN.
    @Transactional
    public CustomerEntity activateCustomer(UUID userId){
        CustomerEntity customer = customerRepository.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("Customer not found"));

        customer.setStatus(CustomerStatus.ACTIVE);
        return customerRepository.save(customer);
    }

    // =========== Customer views their OWN profile (self-service) ========== //
    @Transactional
    public CustomerProfileResponse getOwnProfile(UUID callerUserId) {
        CustomerEntity customer = findOwnCustomerOrThrow(callerUserId);
        return toProfileResponse(customer);
    }

    // =================== View a SPECIFIC customer (admin) ================== //
    @Transactional
    public CustomerProfileResponse getCustomer(UUID customerId) {
        CustomerEntity customer = findCustomerOrThrow(customerId);
        return toProfileResponse(customer);
    }

    // ==================== Change customer status (ACTIVE / SUSPENDED) ========= //
    @Transactional
    public CustomerProfileResponse updateCustomerStatus(UUID customerId, CustomerStatusUpdateRequest request) {
        if (request.status() != CustomerStatus.ACTIVE && request.status() != CustomerStatus.SUSPENDED) {
            throw new BadRequestException("Status must be ACTIVE or SUSPENDED");
        }

        CustomerEntity customer = findCustomerOrThrow(customerId);
        applyStatus(customer, request.status());
        return toProfileResponse(customerRepository.save(customer));
    }

    // private methode of the change from PENDING restricted the target status to ACTIVE/SUSPENDED,
    private void applyStatus(CustomerEntity customer, CustomerStatus newStatus) {
        if (customer.getStatus() == newStatus) {
            return;
        }
        UserEntity user = customer.getUser();
        customer.setStatus(newStatus);
        user.setStatus(toUserStatus(newStatus));
        userRepository.save(user);

        walletRepository.findByUserIdAndWalletType(user.getId(), WalletType.CUSTOMER_MAIN)
                .ifPresent(wallet -> {
                    wallet.setStatus(toWalletStatus(newStatus));
                    walletRepository.save(wallet);
                });

        if (newStatus != CustomerStatus.ACTIVE) {
            userSessionRepository.deleteAllByUserId(user.getId());
        }
    }

    private static UserStatus toUserStatus(CustomerStatus status) {
        return switch (status) {
            case ACTIVE -> UserStatus.ACTIVE;
            case SUSPENDED -> UserStatus.SUSPENDED;
            case PENDING -> throw new IllegalStateException("PENDING is not a settable target status");
        };
    }

    private static WalletStatus toWalletStatus(CustomerStatus status) {
        return switch (status) {
            case ACTIVE -> WalletStatus.ACTIVE;
            case SUSPENDED -> WalletStatus.SUSPENDED;
            case PENDING -> throw new IllegalStateException("PENDING is not a settable target status");
        };
    }

    // =================== List ALL customers (admin) ================== //
    @Transactional
    public CustomerListResponse listCustomers(Pageable pageable, CustomerStatus status) {
        Page<CustomerEntity> page = status != null
                ? customerRepository.findByStatus(status, pageable)
                : customerRepository.findAll(pageable);

        List<CustomerSummaryResponse> customers = page.getContent().stream()
                .map(this::toSummaryResponse)
                .toList();

        return new CustomerListResponse(
                SummaryList(),
                customers,
                page.getNumber(),
                page.getSize(),
                page.getTotalElements());
    }

    private CustomerListSummary SummaryList() {
        return new CustomerListSummary(
                customerRepository.count(),
                customerRepository.countByStatus(CustomerStatus.ACTIVE),
                customerRepository.countByStatus(CustomerStatus.SUSPENDED),
                walletRepository.sumBalanceByWalletType(WalletType.CUSTOMER_MAIN));
    }

    private CustomerEntity findCustomerOrThrow(UUID customerId) {
        return customerRepository.findById(customerId)
                .orElseThrow(() -> new BadRequestException("Customer not found"));
    }

    private CustomerEntity findOwnCustomerOrThrow(UUID callerUserId) {
        return customerRepository.findByUserId(callerUserId)
                .orElseThrow(() -> new BadRequestException("No customer profile for this account"));
    }

    private CustomerProfileResponse toProfileResponse(CustomerEntity customer) {
        UserEntity user = customer.getUser();
        WalletEntity wallet = walletRepository.findByUserIdAndWalletType(user.getId(), WalletType.CUSTOMER_MAIN)
                .orElseThrow(() -> new IllegalStateException("Customer wallet not initialized"));

        List<TransactionEntity> recent = transactionRepository.findRecentByWalletIdIn(List.of(wallet.getId()), PageRequest.of(0, 5));

        return new CustomerProfileResponse(
                customer.getId(),
                user.getLoginUsername(),
                fullName(customer),
                customer.getEmail(),
                customer.getPhoneNumber(),
                customer.getNidaNumber(),
                customer.getDateOfBirth(),
                customer.getStatus().name(),
                toTransactionSummary(wallet),
                toWalletSummary(wallet),
                toTransactionItems(wallet.getId(), recent),
                customer.getCreatedAt(),
                customer.getKycStatus().name());
    }

    private CustomerSummaryResponse toSummaryResponse(CustomerEntity customer) {
        WalletEntity wallet = walletRepository
                .findByUserIdAndWalletType(customer.getUser().getId(), WalletType.CUSTOMER_MAIN)
                .orElse(null);

        return new CustomerSummaryResponse(
                customer.getId(),
                fullName(customer),
                customer.getPhoneNumber(),
                customer.getEmail(),
                customer.getStatus().name(),
                wallet != null ? toWalletSummary(wallet) : null,
                customer.getCreatedAt(),
                customer.getKycStatus().name());
    }

    private CustomerWalletSummary toWalletSummary(WalletEntity wallet) {
        return new CustomerWalletSummary(
                wallet.getAccountNumber(),
                wallet.getWalletType().name(),
                wallet.getCurrency(),
                wallet.getBalance(),
                wallet.getStatus().name());
    }

    private CustomerTransactionSummary toTransactionSummary(WalletEntity wallet) {
        return new CustomerTransactionSummary(
                wallet.getBalance(),
                transactionRepository.countByWalletId(wallet.getId()),
                transactionRepository.sumAmountInByWalletId(wallet.getId()),
                transactionRepository.sumAmountOutByWalletId(wallet.getId()));
    }

    private List<CustomerTransactionItem> toTransactionItems(UUID walletId, List<TransactionEntity> recent) {
        return recent.stream()
                .map(transaction -> new CustomerTransactionItem(
                        transaction.getReferenceNo(),
                        transaction.getTransactionType(),
                        transaction.getSenderWallet().getId().equals(walletId) ? "DEBIT" : "CREDIT",
                        transaction.getAmount(),
                        transaction.getFee(),
                        transaction.getStatus(),
                        transaction.getCreatedAt()))
                .toList();
    }

    private String fullName(CustomerEntity customer) {
        String middleName = customer.getMiddleName();
        return (middleName == null || middleName.isBlank())
                ? customer.getFirstName() + " " + customer.getLastName()
                : customer.getFirstName() + " " + middleName + " " + customer.getLastName();
    }
}
