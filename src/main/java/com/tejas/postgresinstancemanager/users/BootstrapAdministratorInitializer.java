package com.tejas.postgresinstancemanager.users;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class BootstrapAdministratorInitializer implements ApplicationRunner {

    private final UserService userService;
    private final String username;
    private final String password;

    public BootstrapAdministratorInitializer(
            UserService userService,
            @Value("${app.bootstrap.admin-username:}") String username,
            @Value("${app.bootstrap.admin-password:}") String password) {
        this.userService = userService;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean hasUsername = StringUtils.hasText(username);
        boolean hasPassword = StringUtils.hasText(password);
        if (hasUsername != hasPassword) {
            throw new IllegalStateException(
                    "BOOTSTRAP_ADMIN_USERNAME and BOOTSTRAP_ADMIN_PASSWORD must be configured together.");
        }
        if (hasUsername) {
            if (username.length() > 80 || !username.matches("^[a-zA-Z0-9._-]+$")) {
                throw new IllegalStateException("BOOTSTRAP_ADMIN_USERNAME does not meet the username policy.");
            }
            if (password.length() < 12 || password.length() > 128) {
                throw new IllegalStateException("BOOTSTRAP_ADMIN_PASSWORD must be between 12 and 128 characters.");
            }
            userService.createBootstrapAdministrator(username, password);
        }
    }
}
