import  ballerina/http;

type CountriesArr Countries[];

type CountryInfoArr CountryInfo[];

public client class Client {
    public http:Client clientEp;
    public isolated function init(string serviceUrl = "https://api-cov19.now.sh/", http:ClientConfiguration  httpClientConfig =  {}) returns error? {
        http:Client httpEp = check new (serviceUrl, httpClientConfig);
        self.clientEp = httpEp;
    }
    remote isolated function getCovidinAllCountries() returns CountriesArr|error {
        string  path = string `/api`;
        CountriesArr response = check self.clientEp->get(path, targetType = CountriesArr);
        return response;
    }
    remote isolated function getCountryList() returns CountryInfoArr|error {
        string  path = string `/api/v1/countries/list/`;
        CountryInfoArr response = check self.clientEp->get(path, targetType = CountryInfoArr);
        return response;
    }
    remote isolated function getCountryByName(string country) returns Country|error {
        string  path = string `/api/countries/${country}`;
        Country response = check self.clientEp->get(path, targetType = Country);
        return response;
    }
}
