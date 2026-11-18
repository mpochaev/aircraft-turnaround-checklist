package ru.miit.uvp411.checklist.auth.web;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.ResponseStatus;

import ru.miit.uvp411.checklist.auth.internal.AuthManagementService;
import ru.miit.uvp411.checklist.shared.security.CurrentUser;
import ru.miit.uvp411.checklist.shared.web.Paging;

@RestController
@RequestMapping("/api/v1")
class AuthManagementController {

    private final AuthManagementService service;

    AuthManagementController(AuthManagementService service) { this.service = service; }

    @GetMapping("/users")
    @PreAuthorize("hasAuthority('users.read')")
    Map<String,Object> users(@RequestParam(required=false) String role,
                             @RequestParam(required=false) Boolean isActive,
                             @RequestParam(defaultValue="1") int page,
                             @RequestParam(defaultValue="50") int size) {
        Paging.of(page,size);
        return service.listUsers(role,isActive,page,size);
    }

    @PostMapping("/users")
    @PreAuthorize("hasAuthority('users.write')")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String,Object> createUser(@RequestBody AuthManagementService.UserInput body) {
        return service.createUser(body);
    }

    @PatchMapping("/users/{id}")
    @PreAuthorize("hasAuthority('users.edit')")
    Map<String,Object> updateUser(@PathVariable int id,@RequestBody AuthManagementService.UserPatch body) {
        return service.updateUser(id,body,CurrentUser.get().login());
    }

    @PostMapping("/auth/password-reset")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String,Object> passwordReset(@RequestBody AuthManagementService.PasswordRequest body) {
        return service.createPasswordRequest(body);
    }

    @PostMapping("/me/requests")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String,Object> personalRequest(@RequestBody AuthManagementService.PersonalRequest body) {
        return service.createPersonalRequest(CurrentUser.get().login(),body);
    }

    @GetMapping("/change-requests")
    @PreAuthorize("hasAuthority('requests.read')")
    Map<String,Object> requests(@RequestParam(required=false) String status,
                                @RequestParam(required=false) String kind,
                                @RequestParam(defaultValue="1") int page,
                                @RequestParam(defaultValue="50") int size) {
        Paging.of(page,size);
        return service.listRequests(status,kind,page,size);
    }

    @PatchMapping("/change-requests/{id}")
    @PreAuthorize("hasAuthority('requests.review')")
    Map<String,Object> review(@PathVariable long id,@RequestBody AuthManagementService.ReviewRequest body) {
        return service.reviewRequest(id,body,CurrentUser.get().login());
    }

    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('roles.read')")
    List<Map<String,Object>> roles() { return service.listRoles(); }

    @PostMapping("/roles")
    @PreAuthorize("hasAuthority('roles.write')")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String,Object> createRole(@RequestBody AuthManagementService.RoleInput body) {
        return service.createRole(body);
    }

    @PatchMapping("/roles/{id}")
    @PreAuthorize("hasAuthority('roles.edit')")
    Map<String,Object> updateRole(@PathVariable int id,@RequestBody AuthManagementService.RolePatch body) {
        return service.updateRole(id,body);
    }

    @GetMapping("/permissions")
    @PreAuthorize("hasAuthority('roles.read')")
    List<Map<String,Object>> permissions() { return service.listPermissions(); }
}
