#include <iostream>
#include <vector>
#include <cmath>
using namespace std;

struct Location {
    int x, y;
};

double distance(const Location &a, const Location &b) {
    return sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y));
}

bool bpm(int rider, const vector<vector<int>> &adj, vector<bool> &visited, vector<int> &matchDriver) {
    for (int driver : adj[rider]) {
        if (!visited[driver]) {
            visited[driver] = true;
            if (matchDriver[driver] < 0 || bpm(matchDriver[driver], adj, visited, matchDriver)) {
                matchDriver[driver] = rider;
                return true;
            }
        }
    }
    return false;
}

int maxMatching(const vector<vector<int>> &adj, int riders, int drivers) {
    vector<int> matchDriver(drivers, -1);
    int result = 0;

    for (int i = 0; i < riders; i++) {
        vector<bool> visited(drivers, false);
        if (bpm(i, adj, visited, matchDriver)) result++;
    }

    cout << "\nFinal Matches:\n";
    for (int i = 0; i < drivers; i++) {
        if (matchDriver[i] != -1)
            cout << "Driver " << i << " assigned to Rider " << matchDriver[i] << endl;
    }

    return result;
}

int main() {
    int riders, drivers;
    double maxDistance;

    cout << "Enter number of Riders: ";
    cin >> riders;
    if (riders <= 0) return 0;

    cout << "Enter number of Drivers: ";
    cin >> drivers;
    if (drivers <= 0) return 0;

    cout << "Enter maximum allowed distance for matching: ";
    cin >> maxDistance;
    if (maxDistance < 0) return 0;

    vector<Location> riderLoc(riders), driverLoc(drivers);

    cout << "\nEnter Rider Locations (x y):\n";
    for (int i = 0; i < riders; i++) cin >> riderLoc[i].x >> riderLoc[i].y;

    cout << "\nEnter Driver Locations (x y):\n";
    for (int i = 0; i < drivers; i++) cin >> driverLoc[i].x >> driverLoc[i].y;

    vector<vector<int>> adj(riders);
    for (int i = 0; i < riders; i++) {
        for (int j = 0; j < drivers; j++) {
            if (distance(riderLoc[i], driverLoc[j]) <= maxDistance)
                adj[i].push_back(j);
        }
    }

    int matches = maxMatching(adj, riders, drivers);
    cout << "\nMaximum number of matches: " << matches << endl;

    return 0;
}
