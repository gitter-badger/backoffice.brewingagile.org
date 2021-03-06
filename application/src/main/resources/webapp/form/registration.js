function RegistrationController($scope, $resource, $window, $timeout, $window) {
	var RegistrationResource = $resource("api/registration/1/", {});
	$scope.lastRegisteredName = "";

	$scope.r = {
		participantName: "",
		participantEmail: "",
		billingCompany: "",
		billingAddress: "",
		billingMethod: "EMAIL", //EMAIL or SNAILMAIL
		dietaryRequirements: "",
		ticket: "conference",
		twitter: ""
	};

	$scope.reset = function() {	
		$scope.success = false;
		$timeout(function() {
			document.getElementById("inputParticipantName").focus(); //this is not the angular way, I know.
		});
	};

	$scope.submit = function() {
		$scope.success = null;
		$scope.error = null;
		RegistrationResource.save($scope.r, function(p) {
			if (p.success) $scope.lastRegisteredName = $scope.r.participantName;
			$scope.success = p.success;
		}, function(response) { 
			$scope.error = true;
		});
	};

  $scope.showForm = function() {
    if ($scope.success) return false;
    return true;
  }
}

RegistrationController.$inject = ['$scope', '$resource', '$window', '$timeout', '$window'];
