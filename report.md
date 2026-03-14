# Automation Script Quality Report

## Summary

- Hard Wait Found: **15**
- Test Data Hardcoding: **0**
- Duplicate Locators: **0**
- Poor Assertions: **0**
- Unused Functions: **0**
- Missing Validations: **28**
- Files Scanned: **10**

## Hard Wait Found

- `/src/test/java/com/hrm/tests/AttendanceTest.java:30:9` - `Assert.assertNotNull(attendanceStatus, "Attendance status should be displayed");`
- `/src/test/java/com/hrm/tests/AttendanceTest.java:41:9` - `Assert.assertNotNull(attendanceStatus, "Attendance status should be displayed");`
- `/src/test/java/com/hrm/tests/AttendanceTest.java:47:9` - `Assert.assertTrue(attendancePage.isAttendanceRecordsTableDisplayed(),`
- `/src/test/java/com/hrm/tests/EmployeeManagementTest.java:30:9` - `Assert.assertNotNull(successMessage, "Success message should be displayed");`
- `/src/test/java/com/hrm/tests/EmployeeManagementTest.java:31:9` - `Assert.assertTrue(successMessage.contains("success") || successMessage.contains("added"),`
- `/src/test/java/com/hrm/tests/EmployeeManagementTest.java:38:9` - `Assert.assertTrue(employeePage.isEmployeeTableDisplayed(),`
- `/src/test/java/com/hrm/tests/EmployeeManagementTest.java:50:9` - `Assert.assertTrue(true, "Form validation should prevent saving empty form");`
- `/src/test/java/com/hrm/tests/LeaveManagementTest.java:30:9` - `Assert.assertNotNull(leaveStatus, "Leave status should be displayed");`
- `/src/test/java/com/hrm/tests/LeaveManagementTest.java:36:9` - `Assert.assertTrue(leavePage.isLeaveRequestsTableDisplayed(),`
- `/src/test/java/com/hrm/tests/LoginTest.java:16:9` - `Thread.sleep(1000);`
- `/src/test/java/com/hrm/tests/LoginTest.java:19:9` - `Assert.assertTrue(dashboardPage.isDashboardDisplayed(), "Dashboard should be displayed after successful login");`
- `/src/test/java/com/hrm/tests/LoginTest.java:27:9` - `Thread.sleep(1000);`
- `/src/test/java/com/hrm/tests/LoginTest.java:29:9` - `Assert.assertNotNull(errorMessage, "Error message should be displayed");`
- `/src/test/java/com/hrm/tests/LoginTest.java:30:9` - `Assert.assertTrue(errorMessage.contains("Invalid") || errorMessage.contains("incorrect"),`
- `/src/test/java/com/hrm/tests/LoginTest.java:41:9` - `Assert.assertNotNull(errorMessage, "Error message should be displayed for empty credentials");`

## Test Data Hardcoding

No findings.

## Duplicate Locators

No findings.

## Poor Assertions

No findings.

## Unused Functions

No findings.

## Missing Validations

- `/src/main/java/com/hrm/pages/AttendancePage.java:14:19` - `@FindBy(id = "check-in-button")`
- `/src/main/java/com/hrm/pages/AttendancePage.java:17:19` - `@FindBy(id = "check-out-button")`
- `/src/main/java/com/hrm/pages/AttendancePage.java:35:30` - `markAttendanceButton.click();`
- `/src/main/java/com/hrm/pages/AttendancePage.java:39:23` - `checkInButton.click();`
- `/src/main/java/com/hrm/pages/AttendancePage.java:43:24` - `checkOutButton.click();`
- `/src/main/java/com/hrm/pages/AttendancePage.java:64:29` - `attendanceDateField.sendKeys(date);`
- `/src/main/java/com/hrm/pages/DashboardPage.java:35:23` - `employeesLink.click();`
- `/src/main/java/com/hrm/pages/DashboardPage.java:39:29` - `leaveManagementLink.click();`
- `/src/main/java/com/hrm/pages/DashboardPage.java:43:24` - `attendanceLink.click();`
- `/src/main/java/com/hrm/pages/DashboardPage.java:47:25` - `departmentsLink.click();`
- `/src/main/java/com/hrm/pages/DashboardPage.java:51:22` - `logoutButton.click();`
- `/src/main/java/com/hrm/pages/EmployeeManagementPage.java:42:27` - `addEmployeeButton.click();`
- `/src/main/java/com/hrm/pages/EmployeeManagementPage.java:47:27` - `employeeNameField.sendKeys(name);`
- `/src/main/java/com/hrm/pages/EmployeeManagementPage.java:52:28` - `employeeEmailField.sendKeys(email);`
- `/src/main/java/com/hrm/pages/EmployeeManagementPage.java:62:31` - `employeePositionField.sendKeys(position);`
- `/src/main/java/com/hrm/pages/EmployeeManagementPage.java:66:28` - `saveEmployeeButton.click();`
- `/src/main/java/com/hrm/pages/LeaveManagementPage.java:15:25` - `@FindBy(id = "leave-type")`
- `/src/main/java/com/hrm/pages/LeaveManagementPage.java:27:19` - `@FindBy(id = "submit-leave-button")`
- `/src/main/java/com/hrm/pages/LeaveManagementPage.java:42:26` - `applyLeaveButton.click();`
- `/src/main/java/com/hrm/pages/LeaveManagementPage.java:52:24` - `startDateField.sendKeys(startDate);`
- `/src/main/java/com/hrm/pages/LeaveManagementPage.java:57:22` - `endDateField.sendKeys(endDate);`
- `/src/main/java/com/hrm/pages/LeaveManagementPage.java:62:26` - `leaveReasonField.sendKeys(reason);`
- `/src/main/java/com/hrm/pages/LeaveManagementPage.java:66:27` - `submitLeaveButton.click();`
- `/src/main/java/com/hrm/pages/LoginPage.java:33:23` - `usernameField.sendKeys(username);`
- `/src/main/java/com/hrm/pages/LoginPage.java:38:23` - `passwordField.sendKeys(password);`
- `/src/main/java/com/hrm/pages/LoginPage.java:42:21` - `loginButton.click();`
- `/src/test/java/com/hrm/tests/AttendanceTest.java:23:47` - `@Test(priority = 1, description = "Verify check-in functionality")`
- `/src/test/java/com/hrm/tests/AttendanceTest.java:33:47` - `@Test(priority = 2, description = "Verify check-out functionality")`
